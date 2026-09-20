package top.michubil.musictag.data.flac

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.rename.RenameTag
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.concurrent.CancellationException

class FlacTextMetadataReaderTest {
    private val magic = "fLaC".toByteArray(Charsets.US_ASCII)
    private val info = block(STREAM_INFO, ByteArray(34))
    private val comments = VorbisComments("test", listOf("TITLE=歌曲", "ARTIST=甲", "ARTIST=乙")).encode()

    @Test
    fun stopsAfterCommentsWithoutReadingLaterCoverOrAudio() {
        val prefix = magic + info + block(VORBIS_COMMENT, comments)
        val input = CountingInput(prefix + block(PICTURE, ByteArray(256 * 1024), last = true) + ByteArray(1024), prefix.size)
        val tags = FlacCodec.readTextMetadata(input)
        assertEquals("歌曲", tags.values[RenameTag.TITLE])
        assertEquals("甲 & 乙", tags.values[RenameTag.ARTISTS])
        assertEquals(listOf("甲", "乙"), tags.artists)
        assertEquals(prefix.size, input.readCount)
    }

    @Test
    fun skipsPictureBeforeCommentsWithoutAllocatingOrReadingItsPayload() {
        val coverSize = 256 * 1024
        val metadata = magic + info + block(PICTURE, ByteArray(coverSize)) + block(VORBIS_COMMENT, comments, last = true)
        val input = CountingInput(metadata + ByteArray(1024))
        assertEquals("歌曲", FlacCodec.readTextMetadata(input).values[RenameTag.TITLE])
        assertEquals(coverSize.toLong(), input.skipCount)
        assertEquals(metadata.size - coverSize, input.readCount)
    }

    @Test
    fun absentCommentsDoNotReadAudioAndTruncatedMetadataFails() {
        val metadata = magic + block(STREAM_INFO, ByteArray(34), last = true)
        val input = CountingInput(metadata + ByteArray(1024), metadata.size)
        assertTrue(FlacCodec.readTextMetadata(input).values.isEmpty())
        assertEquals(metadata.size, input.readCount)
        assertThrows(EOFException::class.java) { FlacCodec.readTextMetadata(metadata.copyOf(20).inputStream()) }
        val truncatedComment = (magic + info + block(VORBIS_COMMENT, comments, last = true)).dropLast(1).toByteArray()
        assertThrows(EOFException::class.java) { FlacCodec.readTextMetadata(truncatedComment.inputStream()) }
    }

    @Test
    fun cancellationStopsBeforeReadingTheNextBlock() {
        val input = CountingInput(magic + info + block(VORBIS_COMMENT, comments, last = true))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FlacCodec.readTextMetadata(input) { if (++checks == 2) throw CancellationException() }
        }
        assertEquals(42, input.readCount)
    }

    private fun block(type: Int, data: ByteArray, last: Boolean = false): ByteArray = byteArrayOf(
        (type or (if (last) 0x80 else 0)).toByte(),
        (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte(),
    ) + data

    private class CountingInput(bytes: ByteArray, private val readLimit: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        var readCount = 0
        var skipCount = 0L

        override fun read(): Int {
            check(pos < readLimit) { "Reader reached unneeded cover/audio data" }
            return super.read().also { if (it >= 0) readCount++ }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(pos.toLong() + length <= readLimit) { "Reader reached unneeded cover/audio data" }
            return super.read(bytes, offset, length).also { if (it > 0) readCount += it }
        }

        override fun skip(count: Long): Long = super.skip(count).also { skipCount += it }
    }
}
