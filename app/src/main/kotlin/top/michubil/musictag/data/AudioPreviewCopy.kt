package top.michubil.musictag.data

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import top.michubil.musictag.data.flac.FLAC_MAGIC
import top.michubil.musictag.data.flac.FLAC_MAGIC_BYTES
import top.michubil.musictag.data.id3.leadingId3TagSize

/** Copies only the leading metadata used by the existing FLAC/ID3 readers. Never used for edits. */
internal fun copyPreviewMetadata(input: InputStream, output: OutputStream, extension: String, checkActive: () -> Unit) {
    val source = DataInputStream(input)
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    fun copyExactly(length: Int) {
        require(length >= 0 && copied + length <= 32L * 1024 * 1024) { "Preview metadata is too large" }
        copied += length
        var remaining = length
        while (remaining > 0) {
            checkActive()
            val count = minOf(remaining, buffer.size)
            source.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    checkActive()
    when (extension) {
        "flac" -> {
            val magic = source.readInt()
            require(magic == FLAC_MAGIC) { "Not a FLAC file" }
            output.write(FLAC_MAGIC_BYTES)
            copied = 4
            do {
                checkActive()
                val header = ByteArray(4).also(source::readFully)
                output.write(header)
                copied += 4
                val length = ((header[1].toInt() and 255) shl 16) or
                    ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                copyExactly(length)
            } while (header[0].toInt() and 0x80 == 0)
        }
        "mp3" -> {
            val header = source.readNBytes(10)
            output.write(header)
            copied = header.size.toLong()
            val tagSize = leadingId3TagSize(header) ?: return
            copyExactly(tagSize - header.size)
        }
        else -> error("Unsupported preview format")
    }
    checkActive()
}
