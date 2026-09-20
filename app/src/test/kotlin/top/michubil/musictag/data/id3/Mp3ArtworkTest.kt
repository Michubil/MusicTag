package top.michubil.musictag.data.id3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.michubil.musictag.data.copyPreviewMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Mp3ArtworkTest {
    @Test
    fun unclassifiedV23JpegDecodesFromFullFileAndPreviewMetadata() = withTemporaryMp3 { source ->
        val jpeg = jpeg()
        // Observed source structure: v2.3, no flags, encoding 0, image/jpg, type 0, empty description.
        source.writeBytes(tag(picture(0, jpeg)) + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2))
        assertNull(Id3Codec.frontCoverBytes(source))
        assertArrayEquals(jpeg, Id3Codec.artworkCandidates(source).single())
        assertDecodable(source)
        withTemporaryMp3 { preview ->
            source.inputStream().use { input ->
                preview.outputStream().use { output -> copyPreviewMetadata(input, output, "mp3") {} }
            }
            assertTrue(preview.length() < source.length())
            assertArrayEquals(jpeg, Id3Codec.artworkCandidates(preview).single())
            assertDecodable(preview)
        }
    }

    @Test
    fun frontCoverTakesPriorityAndDisplayFallbackDoesNotChangeEditingScope() = withTemporaryMp3 { source ->
        val unclassified = jpeg()
        val front = jpeg(width = 3)
        source.writeBytes(tag(picture(0, unclassified), picture(3, front)))
        val candidates = Id3Codec.artworkCandidates(source).toList()
        assertEquals(2, candidates.size)
        assertArrayEquals(front, candidates[0])
        assertArrayEquals(unclassified, candidates[1])
        val frames = Id3Codec.read(source).frames
        assertFalse(Id3Codec.isFrontCover(frames[0]))
        assertTrue(Id3Codec.isFrontCover(frames[1]))
    }

    @Test
    fun backCoversAndFileIconsAreNotUsedAsAlbumArtwork() = withTemporaryMp3 { source ->
        source.writeBytes(tag(picture(4, jpeg()), picture(1, jpeg()), picture(2, jpeg())))
        assertTrue(Id3Codec.artworkCandidates(source).none())
    }

    @Test
    fun readingTagsAndPreviewLeavesSourceUnchanged() = withTemporaryMp3 { source ->
        val before = tag(picture(0, jpeg()), picture(3, jpeg(width = 3))) +
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2)
        source.writeBytes(before)
        try {
            Id3Codec.readEditableTags(source)
            Id3Codec.readLocalTrack(source)
            assertDecodable(source)
            withTemporaryMp3 { preview ->
                source.inputStream().use { input ->
                    preview.outputStream().use { output -> copyPreviewMetadata(input, output, "mp3") {} }
                }
                assertDecodable(preview)
                val full = Id3Codec.artworkCandidates(source).toList()
                val partial = Id3Codec.artworkCandidates(preview).toList()
                assertEquals(full.size, partial.size)
                full.indices.forEach { assertArrayEquals(full[it], partial[it]) }
            }
        } finally {
            assertArrayEquals("Source MP3 must remain unchanged", before, source.readBytes())
        }
    }

    private fun assertDecodable(file: File) {
        val bitmap = Id3Codec.artworkCandidates(file).mapNotNull {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }.firstOrNull()
        assertNotNull("Expected decodable embedded artwork", bitmap)
        requireNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        bitmap.recycle()
    }

    private fun jpeg(width: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(width, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun picture(type: Int, bytes: ByteArray): ByteArray {
        val payload = byteArrayOf(0) + "image/jpg\u0000".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(type.toByte(), 0) + bytes
        val size = payload.size
        return "APIC".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(), 0, 0,
        ) + payload
    }

    private fun tag(vararg frames: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val size = body.size
        return byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0,
            (size ushr 21 and 127).toByte(), (size ushr 14 and 127).toByte(),
            (size ushr 7 and 127).toByte(), (size and 127).toByte()) + body
    }

    private fun withTemporaryMp3(block: (File) -> Unit) {
        val file = Files.createTempFile("mp3-artwork-", ".mp3").toFile()
        try { block(file) } finally { file.delete() }
    }
}
