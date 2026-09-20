package top.michubil.musictag.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import top.michubil.musictag.data.flac.*
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.model.CoverImage

class AudioPreviewCopyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun metadataCopiesPreserveTracksAndCoversWithExistingCodecs() {
        val audio = directory.resolve("payload").toFile().apply { writeBytes(ByteArray(1_000_000)) }
        val cover = CoverImage(byteArrayOf(1, 2, 3), "image/png", 1, 1)
        val flac = directory.resolve("original.flac").toFile()
        FlacCodec.writeMetadata(flac, listOf(
            FlacBlock(STREAM_INFO, ByteArray(34)),
            FlacBlock(VORBIS_COMMENT, VorbisComments("test", listOf("TITLE=Song", "ARTIST=Artist", "ALBUM=Album")).encode()),
            FlacBlock(PICTURE, FlacPicture.frontCover(cover).encode()),
        ), audio, 0)
        val flacPreview = directory.resolve("preview.flac").toFile().apply { writeBytes(copy(flac.readBytes(), "flac")) }
        assertEquals(FlacCodec.readLocalTrack(flac).copy(fileName = flacPreview.name), FlacCodec.readLocalTrack(flacPreview))
        assertArrayEquals(FlacCodec.frontCoverBytes(flac), FlacCodec.frontCoverBytes(flacPreview))
        assertEquals(flac.length() - audio.length(), flacPreview.length())

        val mp3 = directory.resolve("original.mp3").toFile().apply {
            writeBytes(Id3Codec.renderTag(listOf(
                requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song"))),
                Id3Codec.frontCoverFrame("image/png", cover.bytes),
            )) + audio.readBytes())
        }
        val mp3Preview = directory.resolve("preview.mp3").toFile().apply { writeBytes(copy(mp3.readBytes(), "mp3")) }
        assertEquals(Id3Codec.readLocalTrack(mp3).copy(fileName = mp3Preview.name), Id3Codec.readLocalTrack(mp3Preview))
        assertArrayEquals(Id3Codec.frontCoverBytes(mp3), Id3Codec.frontCoverBytes(mp3Preview))
        assertEquals(mp3.length() - audio.length(), mp3Preview.length())
    }
    private fun copy(bytes: ByteArray, extension: String): ByteArray = ByteArrayOutputStream().also {
        copyPreviewMetadata(bytes.inputStream(), it, extension) {}
    }.toByteArray()

    @Test
    fun flacCopiesMetadataAndNeverReadsAudioPayload() {
        val metadata = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + ByteArray(34) +
            byteArrayOf(0x84.toByte(), 0, 0, 3, 1, 2, 3)
        val input = object : ByteArrayInputStream(metadata) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                check(available() > 0) { "Attempted to read audio" }
                return super.read(bytes, off, len)
            }
        }
        val output = ByteArrayOutputStream()
        copyPreviewMetadata(input, output, "flac") {}
        assertArrayEquals(metadata, output.toByteArray())
    }

    @Test
    fun mp3CopiesTagAndOptionalFooterWithoutCopyingAudio() {
        for (footer in listOf(false, true)) {
            val header = byteArrayOf(73, 68, 51, 4, 0, if (footer) 16 else 0, 0, 0, 0, 3)
            val tag = header + byteArrayOf(1, 2, 3) + if (footer) ByteArray(10) else byteArrayOf()
            assertArrayEquals(tag, copy(tag + ByteArray(1_000_000), "mp3"))
        }
        assertEquals(10, copy(ByteArray(1_000_000), "mp3").size)
    }

    @Test
    fun malformedTruncatedAndOversizedTagsFailWithoutFullCopy() {
        assertThrows(EOFException::class.java) { copy("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34), "flac") }
        assertThrows(IllegalArgumentException::class.java) { copy(byteArrayOf(73, 68, 51, 2, 0, 0, 0, 0, 0, 0), "mp3") }
        assertThrows(IllegalArgumentException::class.java) { copy(byteArrayOf(73, 68, 51, 4, 0, 0, 127, 127, 127, 127), "mp3") }
        assertThrows(EOFException::class.java) { copy(byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 10), "mp3") }
    }

    @Test
    fun cancellationStopsDuringMetadataCopy() {
        val metadata = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 2, 0, 0) + ByteArray(131072)
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            copyPreviewMetadata(metadata.inputStream(), output, "flac") { if (++checks == 4) throw CancellationException() }
        }
        assertTrue(output.size() < metadata.size)
    }
}
