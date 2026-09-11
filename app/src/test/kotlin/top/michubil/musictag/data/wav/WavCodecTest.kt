package top.michubil.musictag.data.wav

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.ReleaseDate
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.TrackIndex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class WavCodecTest {
    @Test
    fun blankId3AndPrimaryInfoValuesFallBackConsistentlyAndPreserveExistingAliases() {
        val file = directory.resolve("fallback.wav").toFile()
        val frames = listOf(top.michubil.musictag.data.id3.Id3Frame("TIT2", data = byteArrayOf(3, 32)))
        writeWav(file, listOf("fmt " to pcmFormat(),
            "LIST" to infoList(mapOf("INAM" to "INFO title", "IPRT" to " ", "ITRK" to "3")),
            "id3 " to Id3Codec.renderTag(frames), "data" to byteArrayOf(1, 2, 3, 4)))
        assertEquals("INFO title", WavCodec.readEditableTags(file).text[MetadataField.TITLE])
        assertEquals("INFO title", WavCodec.readLocalTrack(file).title)
        val text = WavCodec.readTextMetadata(file)
        assertEquals("INFO title_03.wav", top.michubil.musictag.data.rename.FilenameTemplate("@1_@5").filename(file.name, text))
        val before = file.readBytes()
        SafeWavEditor().update(file, ScrapedMetadata(title = RemoteValue.Available("new"), track = RemoteValue.Available(TrackIndex(7, null))),
            ScrapeOptions(policies = MetadataField.entries.associateWith { FieldPolicy(overwrite = false) }))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun fillMissingReplacesBlankFramesAndSynchronizesExistingInfoAliases() {
        val file = directory.resolve("blank.wav").toFile()
        val frames = listOf(top.michubil.musictag.data.id3.Id3Frame("TIT2", data = byteArrayOf(3, 32)))
        writeWav(file, listOf("fmt " to pcmFormat(),
            "LIST" to infoList(mapOf("INAM" to " ", "IPRT" to " ", "ITRK" to " ")),
            "id3 " to Id3Codec.renderTag(frames), "data" to byteArrayOf(1, 2, 3, 4)))
        SafeWavEditor().update(file, ScrapedMetadata(title = RemoteValue.Available("new"), track = RemoteValue.Available(TrackIndex(7, null))),
            ScrapeOptions(policies = MetadataField.entries.associateWith { FieldPolicy(overwrite = false) }))
        assertEquals("new", WavCodec.readEditableTags(file).text[MetadataField.TITLE])
        assertEquals("new", WavCodec.readLocalTrack(file).title)
        assertEquals("7", WavCodec.read(file).info["IPRT"])
        assertEquals("7", WavCodec.read(file).info["ITRK"])
    }

    @Test
    fun manualEditingKeepsInfoAliasesConsistentAndPreservesOtherChunks() {
        val file = directory.resolve("manual.wav").toFile()
        val audio = byteArrayOf(1, 2, 3, 4)
        val junk = byteArrayOf(7, 8)
        writeWav(file, listOf("fmt " to pcmFormat(), "LIST" to infoList(mapOf("INAM" to "旧标题", "ITRK" to "3", "ICMT" to "保留")), "JUNK" to junk, "data" to audio))
        assertEquals("3", WavCodec.readEditableTags(file).text[MetadataField.TRACK])
        val draft = top.michubil.musictag.data.edit.TagDraft(
            text = mapOf(MetadataField.TITLE to "标题", MetadataField.ARTISTS to "甲\n乙", MetadataField.ALBUM to "专辑",
                MetadataField.DATE to "2026-09", MetadataField.TRACK to "2/12", MetadataField.DISC to "1/2", MetadataField.LYRICS to "[00:01.234]原文"),
            changed = MetadataField.entries.toSet(), cover = CoverImage(byteArrayOf(7, 8), "image/png", 1, 1),
        )
        val mutation = draft.mutation()
        SafeWavEditor().update(file, mutation.metadata, mutation.options)
        assertEquals(draft.text, WavCodec.readEditableTags(file).text)
        assertTrue(WavCodec.readEditableTags(file).hasCover)
        assertEquals("2/12", WavCodec.read(file).info["ITRK"])
        val clear = top.michubil.musictag.data.edit.TagDraft(changed = setOf(MetadataField.TRACK, MetadataField.TITLE, MetadataField.COVER)).mutation()
        SafeWavEditor().update(file, clear.metadata, clear.options)
        assertEquals("", WavCodec.readEditableTags(file).text[MetadataField.TRACK])
        assertEquals("", WavCodec.readEditableTags(file).text[MetadataField.TITLE])
        assertFalse(WavCodec.readEditableTags(file).hasCover)
        val read = WavCodec.read(file)
        assertEquals("保留", read.info["ICMT"])
        assertArrayEquals(audio, file.readRegion(read.audioRegion.offset, read.audioRegion.length))
        assertArrayEquals(junk, file.readChunk("JUNK"))
    }

    @Test
    fun renameReadsId3BeforeInfoAndNeverChangesRiffBytes() {
        val file = directory.resolve("rename.wav").toFile()
        val frames = listOfNotNull(Id3Codec.textFrame("TIT2", listOf("ID3 title")), Id3Codec.textFrame("TPE2", listOf("Album artist")))
        writeWav(file, listOf(
            "fmt " to pcmFormat(),
            "LIST" to infoList(mapOf("INAM" to "INFO title", "IART" to "Artist", "IPRD" to "Album", "ICRD" to "2026", "ICMT" to "Comment", "ITRK" to "3")),
            "data" to byteArrayOf(1, 2, 3, 4),
            "id3 " to Id3Codec.renderTag(frames),
        ))
        val before = file.readBytes()
        assertEquals("ID3 title_Artist_Album_03_2026_Comment_Album artist.wav",
            top.michubil.musictag.data.rename.FilenameTemplate("@1_@2_@3_@5_@6_@7_@8").filename(file.name, WavCodec.readTextMetadata(file)))
        assertArrayEquals(before, file.readBytes())
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun textMetadataPreservesFullDateWithId3PriorityAndInfoFallback() {
        val file = directory.resolve("date.wav").toFile()
        val chunks = listOf("fmt " to pcmFormat(),
            "LIST" to infoList(mapOf("ICRD" to "2026-09-05")), "data" to byteArrayOf(1, 2, 3, 4))
        writeWav(file, chunks)
        assertEquals("2026-09-05", WavCodec.readTextMetadata(file).date)
        val frames = listOfNotNull(Id3Codec.textFrame("TDRC", listOf("2025-08-03")))
        writeWav(file, chunks + ("id3 " to Id3Codec.renderTag(frames)))
        assertEquals("2025-08-03", WavCodec.readTextMetadata(file).date)
    }

    @Test
    fun safeEditorWritesId3v24WithoutChangingAudioOrOtherChunks() {
        val source = directory.resolve("safe.wav").toFile()
        val audio = byteArrayOf(10, 20, 30, 40)
        val junk = byteArrayOf(1, 2, 3)
        writeWav(source, listOf("fmt " to pcmFormat(), "JUNK" to junk, "data" to audio))
        val cover = byteArrayOf(9, 8, 7, 6)

        SafeWavEditor().update(
            source,
            ScrapedMetadata(
                title = RemoteValue.Available("Title"),
                artists = RemoteValue.Available(listOf("Artist A", "Artist B")),
                album = RemoteValue.Available("Album"),
                date = RemoteValue.Available(ReleaseDate(2026, 9, 3)),
                track = RemoteValue.Available(TrackIndex(2, 12)),
                disc = RemoteValue.Available(TrackIndex(1, 2)),
                lyrics = RemoteValue.Available("[00:01.00]Line"),
                cover = RemoteValue.Available(CoverImage(cover, "image/jpeg", 1, 1)),
            ),
            ScrapeOptions(),
        )

        val written = WavCodec.read(source)
        assertEquals("Title", WavCodec.readLocalTrack(source).title)
        assertEquals(listOf("Artist A", "Artist B"), WavCodec.readLocalTrack(source).artists)
        assertEquals("Album", WavCodec.readLocalTrack(source).album)
        assertEquals(1_000, written.durationMs)
        assertEquals(written.durationMs, WavCodec.readDuration(source.inputStream()))
        assertArrayEquals(cover, Id3Codec.frontCoverBytes(WavCodec.read(source).id3Frames))
        assertArrayEquals(audio, source.readRegion(written.audioRegion.offset, written.audioRegion.length))
        assertArrayEquals(junk, source.readChunk("JUNK"))
        assertTrue(written.id3Frames.any { it.id == "TDRC" })
        assertTrue(written.id3Frames.any { it.id == "TRCK" })
        assertTrue(written.id3Frames.any { it.id == "TPOS" })
        assertTrue(written.id3Frames.any { it.id == "USLT" })
        assertFalse(directory.toFile().listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test
    fun readsLowercaseId3ChunkAndInfoFallback() {
        val source = directory.resolve("read.wav").toFile()
        val frames = listOfNotNull(Id3Codec.textFrame("TIT2", listOf("ID3 title")))
        val info = infoList(mapOf("IART" to "INFO artist", "IPRD" to "INFO album"))
        writeWav(
            source,
            listOf(
                "fmt " to pcmFormat(),
                "LIST" to info,
                "data" to byteArrayOf(1, 2, 3, 4),
                "id3 " to Id3Codec.renderTag(frames, paddingSize = 0),
            ),
        )

        val track = WavCodec.readLocalTrack(source)

        assertEquals("ID3 title", track.title)
        assertEquals(listOf("INFO artist"), track.artists)
        assertEquals("INFO album", track.album)
    }

    @Test
    fun overwriteKeepsExistingInfoAndId3MetadataInSync() {
        val source = directory.resolve("info.wav").toFile()
        val audio = byteArrayOf(4, 3, 2, 1)
        val junk = byteArrayOf(6, 5, 4)
        writeWav(
            source,
            listOf(
                "fmt " to pcmFormat(),
                "LIST" to infoList(mapOf("INAM" to "Old title", "IART" to "Old artist")),
                "JUNK" to junk,
                "data" to audio,
            ),
        )
        val policies = MetadataField.entries.associateWith { FieldPolicy(enabled = false) } + mapOf(
            MetadataField.TITLE to FieldPolicy(overwrite = true),
            MetadataField.ARTISTS to FieldPolicy(overwrite = true),
        )

        SafeWavEditor().update(
            source,
            ScrapedMetadata(
                title = RemoteValue.Available("New title"),
                artists = RemoteValue.Available(listOf("New artist")),
                album = RemoteValue.Unavailable,
                date = RemoteValue.Unavailable,
                track = RemoteValue.Unavailable,
                disc = RemoteValue.Unavailable,
                lyrics = RemoteValue.Unavailable,
                cover = RemoteValue.Unavailable,
            ),
            ScrapeOptions(policies = policies),
        )

        val written = WavCodec.read(source)
        assertEquals("New title", written.info["INAM"])
        assertEquals("New artist", written.info["IART"])
        assertEquals("New title", WavCodec.readLocalTrack(source).title)
        assertEquals(listOf("New artist"), WavCodec.readLocalTrack(source).artists)
        assertArrayEquals(audio, source.readRegion(written.audioRegion.offset, written.audioRegion.length))
        assertArrayEquals(junk, source.readChunk("JUNK"))
    }

    private fun pcmFormat(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(1)
        putShort(2)
        putInt(1)
        putInt(4)
        putShort(4)
        putShort(16)
    }.array()

    private fun infoList(values: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write("INFO".toByteArray(Charsets.US_ASCII))
            values.forEach { (id, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8) + 0
                output.write(id.toByteArray(Charsets.US_ASCII))
                output.writeUInt32Le(bytes.size.toLong())
                output.write(bytes)
                if (bytes.size and 1 != 0) output.write(0)
            }
            output.toByteArray()
        }

    private fun writeWav(file: File, chunks: List<Pair<String, ByteArray>>) {
        val riffSize = 4L + chunks.sumOf { (_, bytes) -> 8L + bytes.size + (bytes.size and 1) }
        file.outputStream().buffered().use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeUInt32Le(riffSize)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            chunks.forEach { (id, bytes) ->
                output.write(id.toByteArray(Charsets.US_ASCII))
                output.writeUInt32Le(bytes.size.toLong())
                output.write(bytes)
                if (bytes.size and 1 != 0) output.write(0)
            }
        }
    }

    private fun File.readChunk(id: String): ByteArray {
        val chunk = WavCodec.read(this).chunks.first { it.id == id }
        return readRegion(chunk.dataOffset, chunk.dataSize)
    }

    private fun File.readRegion(offset: Long, length: Long): ByteArray =
        RandomAccessFile(this, "r").use { source ->
            source.seek(offset)
            ByteArray(length.toInt()).also(source::readFully)
        }
}

private fun OutputStream.writeUInt32Le(value: Long) {
    write(value.toInt())
    write((value ushr 8).toInt())
    write((value ushr 16).toInt())
    write((value ushr 24).toInt())
}
