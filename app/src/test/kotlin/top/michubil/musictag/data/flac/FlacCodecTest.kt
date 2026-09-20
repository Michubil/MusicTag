package top.michubil.musictag.data.flac

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.rename.FilenameTemplate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class FlacCodecTest {
    @Test
    fun fastDurationReadsOnlyStreamInfoAndMatchesFullParser() {
        val file = directory.resolve("duration.flac").toFile()
        writeTinyFlac(file, streamInfo(48_000, 48_000L * 65), ByteArray(1024))
        val bytes = file.readBytes()
        var count = 0
        val headerOnly = object : java.io.InputStream() {
            override fun read(): Int {
                check(count < 42) { "Duration must not read tags or audio" }
                return bytes[count++].toInt() and 255
            }
        }
        assertEquals(65_000L, FlacCodec.readDuration(headerOnly))
        assertEquals(42, count)
        assertEquals(FlacCodec.read(file).durationMs, FlacCodec.readDuration(bytes.inputStream()))
        org.junit.jupiter.api.Assertions.assertThrows(java.io.EOFException::class.java) {
            FlacCodec.readDuration(bytes.copyOf(30).inputStream())
        }
    }

    @Test
    fun manualEditingReadsBackAllValuesAndOnlyClearsSelectedFields() {
        val file = directory.resolve("manual.flac").toFile()
        val audio = byteArrayOf(1, 2, 3, 4)
        writeTinyFlac(file, streamInfo(48_000, 48_000), audio)
        val draft = top.michubil.musictag.data.edit.TagDraft(
            text = mapOf(MetadataField.TITLE to "标题", MetadataField.ARTISTS to "甲\n乙", MetadataField.ALBUM to "专辑",
                MetadataField.DATE to "2026-09", MetadataField.TRACK to "2/12", MetadataField.DISC to "1/2", MetadataField.LYRICS to "[00:01.234]原文"),
            changed = MetadataField.entries.toSet(),
            cover = top.michubil.musictag.data.model.CoverImage(byteArrayOf(7, 8), "image/png", 1, 1),
        )
        val mutation = draft.mutation()
        SafeFlacEditor().update(file, mutation.metadata, mutation.options)
        assertEquals(draft.text, FlacCodec.readEditableTags(file).text)
        assertTrue(FlacCodec.readEditableTags(file).hasCover)
        val clear = top.michubil.musictag.data.edit.TagDraft(changed = setOf(MetadataField.TITLE, MetadataField.COVER)).mutation()
        SafeFlacEditor().update(file, clear.metadata, clear.options)
        assertEquals("", FlacCodec.readEditableTags(file).text[MetadataField.TITLE])
        assertEquals("甲\n乙", FlacCodec.readEditableTags(file).text[MetadataField.ARTISTS])
        assertEquals("2026-09", FlacCodec.readEditableTags(file).text[MetadataField.DATE])
        assertFalse(FlacCodec.readEditableTags(file).hasCover)
        val read = FlacCodec.read(file)
        assertArrayEquals(audio, file.readBytes().copyOfRange(read.audioOffset.toInt(), file.length().toInt()))
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun readsAllRenameFieldsAndMultipleArtistsWithoutChangingFlac() {
        val source = directory.resolve("source.flac").toFile()
        val info = streamInfo(sampleRate = 48_000, totalSamples = 144_000)
        writeTinyFlac(source, info, byteArrayOf(1, 2, 3))
        val comments = VorbisComments("test", listOf("TITLE=歌", "ARTIST=甲", "artist=乙", "ALBUM=专辑", "DISCNUMBER=2/3", "TRACKNUMBER=7/12", "DATE=2026-09-05", "COMMENT=注释", "ALBUMARTIST=丙"))
        val output = directory.resolve("rename.flac").toFile()
        FlacCodec.writeMetadata(output, listOf(FlacBlock(STREAM_INFO, info), FlacBlock(VORBIS_COMMENT, comments.encode())), source, FlacCodec.read(source).audioOffset)
        val before = output.readBytes()
        assertEquals("歌_甲 & 乙_专辑_2_07_2026_注释_丙.flac", FilenameTemplate("@1_@2_@3_@4_@5_@6_@7_@8").filename(output.name, FlacCodec.readTextMetadata(output)))
        assertArrayEquals(before, output.readBytes())
    }

    @Test
    fun `vorbis comment round trip keeps duplicate artists`() {
        val original = VorbisComments("encoder", listOf("TITLE=Track", "ARTIST=One", "ARTIST=Two", "163 key=value"))
        val decoded = VorbisComments.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(listOf("One", "Two"), decoded.values("artist"))
    }

    @Test
    fun `targeted mutation preserves unmanaged comments and non-front pictures`() {
        val comments = VorbisComments("encoder", listOf("TITLE=Old", "ARTIST=Artist", "CUSTOM=keep"))
        val backPicture = FlacPicture(4, "image/jpeg", "Back", 1, 1, 24, 0, byteArrayOf(1, 2, 3)).encode()
        val blocks = listOf(
            FlacBlock(STREAM_INFO, ByteArray(34)),
            FlacBlock(VORBIS_COMMENT, comments.encode()),
            FlacBlock(PICTURE, backPicture),
            FlacBlock(PADDING, ByteArray(16)),
        )
        val policies = ScrapeOptions().policies.mapValues { (field, policy) ->
            policy.copy(enabled = field == MetadataField.TITLE, overwrite = field == MetadataField.TITLE)
        }
        val metadata = emptyMetadata(title = RemoteValue.Available("New"))

        val result = SafeFlacEditor().mutateBlocks(blocks, metadata, ScrapeOptions(policies))
        val updated = VorbisComments.decode(result.first { it.type == VORBIS_COMMENT }.data)

        assertEquals(listOf("New"), updated.values("TITLE"))
        assertEquals(listOf("Artist"), updated.values("ARTIST"))
        assertEquals(listOf("keep"), updated.values("CUSTOM"))
        assertArrayEquals(backPicture, result.first { it.type == PICTURE }.data)
    }

    @Test
    fun `reads duration and keeps audio payload while rewriting metadata`() {
        val source = directory.resolve("source.flac").toFile()
        val streamInfo = streamInfo(sampleRate = 48_000, totalSamples = 144_000)
        val audio = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 1, 2, 3, 4)
        writeTinyFlac(source, streamInfo, audio)

        val parsed = FlacCodec.read(source)
        assertEquals(3_000, parsed.durationMs)
        val output = directory.resolve("output.flac").toFile()
        val comments = FlacBlock(VORBIS_COMMENT, VorbisComments("test", listOf("TITLE=Track")).encode())
        FlacCodec.writeMetadata(output, listOf(FlacBlock(STREAM_INFO, streamInfo), comments), source, parsed.audioOffset)
        val written = FlacCodec.read(output)
        assertArrayEquals(audio, output.readBytes().copyOfRange(written.audioOffset.toInt(), output.length().toInt()))
        assertFalse(parsed.comments != null)
        assertTrue(written.comments?.values("TITLE") == listOf("Track"))
    }

    @Test
    fun safeEditorAtomicallyUpdatesMetadataWithoutChangingAudio() {
        val source = directory.resolve("atomic.flac").toFile()
        val streamInfo = streamInfo(sampleRate = 44_100, totalSamples = 88_200)
        val audio = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 9, 8, 7, 6)
        writeTinyFlac(source, streamInfo, audio)
        val policies = ScrapeOptions().policies.mapValues { (field, policy) ->
            policy.copy(enabled = field == MetadataField.TITLE, overwrite = field == MetadataField.TITLE)
        }

        SafeFlacEditor().update(
            source,
            emptyMetadata(RemoteValue.Available("Written safely")),
            ScrapeOptions(policies),
        )

        val written = FlacCodec.read(source)
        assertEquals(listOf("Written safely"), written.comments?.values("TITLE"))
        assertArrayEquals(audio, source.readBytes().copyOfRange(written.audioOffset.toInt(), source.length().toInt()))
        assertEquals(emptyList<File>(), directory.toFile().listFiles()?.filter { it.extension == "tmp" })
    }

    private fun emptyMetadata(title: RemoteValue<String>) = ScrapedMetadata(
        title = title,
        artists = RemoteValue.Unavailable,
        album = RemoteValue.Unavailable,
        date = RemoteValue.Unavailable,
        track = RemoteValue.Unavailable,
        disc = RemoteValue.Unavailable,
        lyrics = RemoteValue.Unavailable,
        cover = RemoteValue.Unavailable,
    )

    private fun streamInfo(sampleRate: Int, totalSamples: Long): ByteArray = ByteArray(34).also { bytes ->
        val packed = (sampleRate.toULong() shl 44) or totalSamples.toULong()
        ByteBuffer.wrap(bytes, 10, 8).order(ByteOrder.BIG_ENDIAN).putLong(packed.toLong())
    }

    private fun writeTinyFlac(file: File, streamInfo: ByteArray, audio: ByteArray) {
        file.outputStream().use { output ->
            output.write("fLaC".toByteArray())
            output.write(0x80 or STREAM_INFO)
            output.write(0)
            output.write(0)
            output.write(streamInfo.size)
            output.write(streamInfo)
            output.write(audio)
        }
    }
}
