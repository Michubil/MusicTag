package top.michubil.musictag.data

import top.michubil.musictag.data.model.MetadataField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.michubil.musictag.data.id3.Id3Frame
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.model.Mp3TagVersion
import java.nio.file.Path

class AudioMetadataReaderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun textAndArtworkShareTheParsedSnapshotForBothMp3Versions() {
        for (version in Mp3TagVersion.entries) {
            val file = directory.resolve("preview-${version.major}.mp3").toFile()
            val front = byteArrayOf(10, 20, 30)
            val fallback = byteArrayOf(40, 50, 60)
            fun picture(type: Int, bytes: ByteArray) = Id3Frame("APIC", data =
                byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, type.toByte(), 0) + bytes)
            val frames = listOf(requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song"))),
                picture(0, fallback), picture(3, front))
            file.writeBytes(Id3Codec.renderTag(frames, version = version))
            val metadata = AudioMetadataReader.readPreview(file)
            // Evaluating artwork must not reopen the file or observe later changes.
            file.writeBytes(byteArrayOf())
            assertEquals("Song", metadata.track.getOrThrow().title)
            val pictures = metadata.pictures.getOrThrow().toList()
            assertEquals(2, pictures.size)
            assertArrayEquals(front, pictures[0])
            assertArrayEquals(fallback, pictures[1])
        }
    }

    @Test
    fun parsedEditorDataDoesNotNeedTheFileAgain() {
        val file = directory.resolve("song.mp3").toFile()
        val artwork = byteArrayOf(10, 20, 30)
        val picture = Id3Frame("APIC", data = byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 3, 0) + artwork)
        file.writeBytes(Id3Codec.renderTag(listOf(requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song"))), picture)))
        val single = AudioMetadataReader.readEditor(file, includeArtwork = true)
        val batch = AudioMetadataReader.readEditor(file, includeArtwork = false)
        file.writeBytes(byteArrayOf())
        assertEquals("Song", single.tags.text[MetadataField.TITLE])
        assertEquals(single.tags, batch.tags)
        assertTrue(single.tags.hasCover)
        assertArrayEquals(artwork, single.pictures.getOrThrow().single())
        assertTrue(batch.pictures.getOrThrow().none())
    }

    @Test
    fun trackReadingUsesTheSameFormatSnapshotAsPreview() {
        val mp3 = directory.resolve("song.mp3").toFile()
        mp3.writeBytes(Id3Codec.renderTag(listOf(requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song"))))))
        assertEquals(AudioMetadataReader.readPreview(mp3).track.getOrThrow(), AudioMetadataReader.readTrack(mp3))
    }
}
