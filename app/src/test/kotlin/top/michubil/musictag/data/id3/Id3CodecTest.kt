package top.michubil.musictag.data.id3

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.ReleaseDate
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.TrackIndex
import top.michubil.musictag.data.rename.FilenameTemplate
import top.michubil.musictag.data.rename.RenameTag
import java.nio.file.Path

class Id3CodecTest {
    @Test
    fun manualEditingReadsBackValuesAndPreservesUncheckedFramesAndAudio() {
        val file = directory.resolve("manual.mp3").toFile()
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3, 4)
        val custom = Id3Frame("TXXX", data = byteArrayOf(3, 75, 0, 86))
        file.writeBytes(Id3Codec.renderTag(listOf(custom)) + audio)
        val draft = top.michubil.musictag.data.edit.TagDraft(
            text = mapOf(MetadataField.TITLE to "标题", MetadataField.ARTISTS to "甲\n乙", MetadataField.ALBUM to "专辑",
                MetadataField.DATE to "2026", MetadataField.TRACK to "2/12", MetadataField.DISC to "1/2", MetadataField.LYRICS to "[00:01.234]原文"),
            changed = MetadataField.entries.toSet(), cover = CoverImage(byteArrayOf(7, 8), "image/png", 1, 1),
        )
        val mutation = draft.mutation()
        SafeMp3Editor().update(file, mutation.metadata, mutation.options)
        assertEquals(draft.text, Id3Codec.readEditableTags(file).text)
        assertTrue(Id3Codec.readEditableTags(file).hasCover)
        val clear = top.michubil.musictag.data.edit.TagDraft(changed = setOf(MetadataField.LYRICS, MetadataField.COVER)).mutation()
        SafeMp3Editor().update(file, clear.metadata, clear.options)
        assertEquals("", Id3Codec.readEditableTags(file).text[MetadataField.LYRICS])
        assertEquals("2026", Id3Codec.readEditableTags(file).text[MetadataField.DATE])
        assertFalse(Id3Codec.readEditableTags(file).hasCover)
        val read = Id3Codec.read(file)
        assertArrayEquals(custom.data, read.frames.first { it.id == "TXXX" }.data)
        assertArrayEquals(audio, file.readBytes().copyOfRange(read.audioOffset.toInt(), file.length().toInt()))
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun readsAllRenameFieldsFromId3v24WithoutChangingFile() {
        val file = directory.resolve("rename.mp3").toFile()
        val frames = mapOf("TIT2" to "标题", "TPE1" to "艺术家", "TALB" to "专辑", "TPOS" to "2/3", "TRCK" to "7/12", "TDRC" to "2026-09-05", "TPE2" to "专辑艺术家")
            .map { (key, value) -> requireNotNull(Id3Codec.textFrame(key, listOf(value))) } +
            Id3Frame("COMM", data = byteArrayOf(3) + "eng描述\u0000注释".toByteArray(Charsets.UTF_8))
        val bytes = Id3Codec.renderTag(frames) + byteArrayOf(1, 2, 3)
        file.writeBytes(bytes)
        val tags = Id3Codec.readTextMetadata(file)
        assertEquals("标题_艺术家_专辑_2_07_2026_注释_专辑艺术家.mp3", FilenameTemplate("@1_@2_@3_@4_@5_@6_@7_@8").filename(file.name, tags))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun commentsRespectEncodingAndSkipMalformedOrUnsupportedFrames() {
        listOf(Charsets.ISO_8859_1, Charsets.UTF_16, Charsets.UTF_16BE, Charsets.UTF_8).forEachIndexed { encoding, charset ->
            val terminator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
            val frame = Id3Frame("COMM", data = byteArrayOf(encoding.toByte()) + "eng".toByteArray() + "descriptor".toByteArray(charset) + terminator + "comment".toByteArray(charset))
            assertEquals("comment", Id3Codec.readTextMetadata(listOf(frame)).values[RenameTag.COMMENT])
            assertNull(Id3Codec.readTextMetadata(listOf(frame.copy(flags = 0x0008))).values[RenameTag.COMMENT])
        }
        val malformed = Id3Frame("COMM", data = byteArrayOf(3) + "engunterminated".toByteArray())
        assertNull(Id3Codec.readTextMetadata(listOf(malformed)).values[RenameTag.COMMENT])
    }

    @Test
    fun `reads ID3v2_4 UTF-8 text and front cover`() {
        val source = directory.resolve("read.mp3").toFile()
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 1, 2, 3)
        source.writeBytes(audio)
        val cover = byteArrayOf(9, 8, 7)
        val frames = listOfNotNull(
            Id3Codec.textFrame("TIT2", listOf("Song")),
            Id3Codec.textFrame("TPE1", listOf("One", "Two")),
            Id3Codec.textFrame("TALB", listOf("Album")),
            Id3Codec.frontCoverFrame("image/jpeg", cover),
        )
        val tagged = directory.resolve("tagged.mp3").toFile()
        Id3Codec.writeTag(tagged, frames, source, 0, paddingSize = 0)

        val track = Id3Codec.readLocalTrack(tagged)

        assertEquals("Song", track.title)
        assertEquals(listOf("One", "Two"), track.artists)
        assertEquals("Album", track.album)
        assertNull(track.durationMs)
        assertArrayEquals(cover, Id3Codec.frontCoverBytes(tagged))
        val parsed = Id3Codec.read(tagged)
        assertArrayEquals(audio, tagged.readBytes().copyOfRange(parsed.audioOffset.toInt(), tagged.length().toInt()))
    }

    @Test
    fun `targeted mutation keeps unknown frames and non-front pictures`() {
        val unknown = Id3Frame("TXXX", data = byteArrayOf(3, 75, 0, 86))
        val backCover = Id3Frame(
            "APIC",
            data = byteArrayOf(3) + "image/png".toByteArray() + byteArrayOf(0, 4, 0, 4, 5, 6),
        )
        val original = listOfNotNull(
            Id3Codec.textFrame("TIT2", listOf("Old")),
            Id3Codec.textFrame("TPE1", listOf("Keep artist")),
            unknown,
            backCover,
        )
        val policies = onlyEnabled(MetadataField.TITLE)

        val result = Id3TagEditor.mutateFrames(
            original,
            metadata(title = RemoteValue.Available("New")),
            ScrapeOptions(policies),
        )

        assertTrue(result.any { it.id == "TIT2" && it.data.copyOfRange(1, it.data.size).toString(Charsets.UTF_8) == "New" })
        assertTrue(result.any { it === unknown })
        assertTrue(result.any { it === backCover })
        assertTrue(result.any { it.id == "TPE1" })
    }

    @Test
    fun `safe editor writes every supported field without changing MP3 payload`() {
        val source = directory.resolve("safe.mp3").toFile()
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 10, 20, 30, 40)
        source.writeBytes(audio)
        val cover = byteArrayOf(1, 3, 5, 7)
        val metadata = ScrapedMetadata(
            title = RemoteValue.Available("Title"),
            artists = RemoteValue.Available(listOf("Artist A", "Artist B")),
            album = RemoteValue.Available("Album"),
            date = RemoteValue.Available(ReleaseDate(2026, 9, 3)),
            track = RemoteValue.Available(TrackIndex(2, 12)),
            disc = RemoteValue.Available(TrackIndex(1, 2)),
            lyrics = RemoteValue.Available("[00:01.00]Line"),
            cover = RemoteValue.Available(CoverImage(cover, "image/jpeg", 1, 1)),
        )

        SafeMp3Editor().update(source, metadata, ScrapeOptions())

        val document = Id3Codec.read(source)
        assertEquals(4, source.readBytes()[3].toInt())
        assertTrue(document.frames.any { it.id == "TDRC" })
        assertTrue(document.frames.any { it.id == "TRCK" })
        assertTrue(document.frames.any { it.id == "TPOS" })
        assertTrue(document.frames.any { it.id == "USLT" })
        assertArrayEquals(cover, Id3Codec.frontCoverBytes(source))
        assertArrayEquals(audio, source.readBytes().copyOfRange(document.audioOffset.toInt(), source.length().toInt()))
        assertFalse(directory.toFile().listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test
    fun `does not overwrite existing frame when overwrite is off`() {
        val existing = listOfNotNull(Id3Codec.textFrame("TIT2", listOf("Existing")))
        val result = Id3TagEditor.mutateFrames(
            existing,
            metadata(title = RemoteValue.Available("Incoming")),
            ScrapeOptions(onlyEnabled(MetadataField.TITLE, overwrite = false)),
        )

        assertTrue(Id3Codec.framesContentEquals(existing, result))
    }

    private fun onlyEnabled(
        enabled: MetadataField,
        overwrite: Boolean = true,
    ) = ScrapeOptions().policies.mapValues { (field, policy) ->
        policy.copy(enabled = field == enabled, overwrite = overwrite && field == enabled)
    }

    private fun metadata(title: RemoteValue<String>) = ScrapedMetadata(
        title = title,
        artists = RemoteValue.Unavailable,
        album = RemoteValue.Unavailable,
        date = RemoteValue.Unavailable,
        track = RemoteValue.Unavailable,
        disc = RemoteValue.Unavailable,
        lyrics = RemoteValue.Unavailable,
        cover = RemoteValue.Unavailable,
    )
}
