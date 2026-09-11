package top.michubil.musictag.data.flac

import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.artistLines
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.rename.AudioTextMetadata
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

internal const val STREAM_INFO = 0
internal const val VORBIS_COMMENT = 4
internal const val FLAC_MAGIC = 0x664C6143
internal val FLAC_MAGIC_BYTES = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
internal const val PICTURE = 6
internal const val PADDING = 1
internal const val FRONT_COVER = 3
internal const val MAX_BLOCK_SIZE = 0xFF_FFFF

internal object FlacKeys {
    const val TITLE = "TITLE"
    const val ARTIST = "ARTIST"
    const val ALBUM = "ALBUM"
    const val DATE = "DATE"
    const val TRACK = "TRACKNUMBER"
    const val TRACK_TOTAL = "TRACKTOTAL"
    const val DISC = "DISCNUMBER"
    const val DISC_TOTAL = "DISCTOTAL"
    const val LYRICS = "LYRICS"
    const val COMMENT = "COMMENT"
    const val DESCRIPTION = "DESCRIPTION"
    const val ALBUMARTIST = "ALBUMARTIST"
    const val ALBUM_ARTIST = "ALBUM ARTIST"
}

internal val flacStringKeys: Map<MetadataField, String> = mapOf(
    MetadataField.TITLE to FlacKeys.TITLE,
    MetadataField.ARTISTS to FlacKeys.ARTIST,
    MetadataField.ALBUM to FlacKeys.ALBUM,
    MetadataField.DATE to FlacKeys.DATE,
    MetadataField.LYRICS to FlacKeys.LYRICS,
)

data class FlacBlock(val type: Int, val data: ByteArray)

data class FlacDocument(
    val blocks: List<FlacBlock>,
    val audioOffset: Long,
    val durationMs: Long?,
) {
    val comments: VorbisComments?
        get() = blocks.firstOrNull { it.type == VORBIS_COMMENT }?.let { VorbisComments.decode(it.data) }

    val frontCover: FlacPicture?
        get() = blocks.asSequence()
            .filter { it.type == PICTURE }
            .mapNotNull { runCatching { FlacPicture.decode(it.data) }.getOrNull() }
            .firstOrNull { it.type == FRONT_COVER }
}

data class VorbisComments(
    val vendor: String,
    val entries: List<String>,
) {
    fun values(key: String): List<String> = entries.mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator > 0 && entry.substring(0, separator).equals(key, ignoreCase = true)) {
            entry.substring(separator + 1)
        } else {
            null
        }
    }

    fun replace(replacements: Map<String, List<String>>): VorbisComments {
        val normalized = replacements.keys.map { it.uppercase() }.toSet()
        val kept = entries.filter { entry ->
            val separator = entry.indexOf('=')
            separator <= 0 || entry.substring(0, separator).uppercase() !in normalized
        }
        val appended = replacements.flatMap { (key, values) -> values.map { "$key=$it" } }
        return copy(entries = kept + appended)
    }

    fun encode(): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val entryBytes = entries.map { it.toByteArray(Charsets.UTF_8) }
        val size = 4L + vendorBytes.size + 4L + entryBytes.sumOf { 4L + it.size }
        require(size <= MAX_BLOCK_SIZE) { "Vorbis comment block is too large" }
        return ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(vendorBytes.size)
            put(vendorBytes)
            putInt(entryBytes.size)
            entryBytes.forEach { bytes ->
                putInt(bytes.size)
                put(bytes)
            }
        }.array()
    }

    companion object {
        fun decode(data: ByteArray): VorbisComments {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val vendor = buffer.readUtf8Le()
            val count = buffer.int.requireNonNegative("Vorbis comment count")
            require(count <= 100_000) { "Too many Vorbis comments" }
            val entries = buildList(count) {
                repeat(count) { add(buffer.readUtf8Le()) }
            }
            require(!buffer.hasRemaining()) { "Trailing bytes in Vorbis comment block" }
            return VorbisComments(vendor, entries)
        }
    }
}

data class FlacPicture(
    val type: Int,
    val mimeType: String,
    val description: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colors: Int,
    val bytes: ByteArray,
) {
    fun encode(): ByteArray {
        val mime = mimeType.toByteArray(Charsets.ISO_8859_1)
        val descriptionBytes = description.toByteArray(Charsets.UTF_8)
        val size = 32L + mime.size + descriptionBytes.size + bytes.size
        require(size <= MAX_BLOCK_SIZE) { "FLAC picture block is too large" }
        return ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(type)
            putInt(mime.size)
            put(mime)
            putInt(descriptionBytes.size)
            put(descriptionBytes)
            putInt(width)
            putInt(height)
            putInt(depth)
            putInt(colors)
            putInt(bytes.size)
            put(bytes)
        }.array()
    }

    companion object {
        fun decode(data: ByteArray): FlacPicture {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val type = buffer.int
            val mime = buffer.readStringBe(Charsets.ISO_8859_1)
            val description = buffer.readStringBe(Charsets.UTF_8)
            require(buffer.remaining() >= 20) { "Truncated FLAC picture block" }
            val width = buffer.int
            val height = buffer.int
            val depth = buffer.int
            val colors = buffer.int
            val pictureLength = buffer.int.requireNonNegative("Picture length")
            require(pictureLength == buffer.remaining()) { "Invalid FLAC picture length" }
            val picture = ByteArray(pictureLength).also(buffer::get)
            return FlacPicture(type, mime, description, width, height, depth, colors, picture)
        }

        fun frontCover(image: CoverImage) = FlacPicture(
            type = FRONT_COVER,
            mimeType = image.mimeType,
            description = "Cover (front)",
            width = image.width,
            height = image.height,
            depth = 24,
            colors = 0,
            bytes = image.bytes,
        )
    }
}

object FlacCodec {
    /** STREAMINFO is the first block; reading duration never loads tags, pictures or audio. */
    fun readDuration(input: InputStream): Long? {
        val source = DataInputStream(input)
        require(source.readInt() == FLAC_MAGIC) { "Not a FLAC file" }
        val type = source.readUnsignedByte() and 0x7F
        val length = (source.readUnsignedByte() shl 16) or (source.readUnsignedByte() shl 8) or source.readUnsignedByte()
        require(type == STREAM_INFO && length == 34) { "Invalid FLAC STREAMINFO" }
        val info = ByteArray(34)
        source.readFully(info)
        return durationFromStreamInfo(info)
    }

    fun readEditableTags(file: File): TagValues = readEditableTags(read(file))

    internal fun readEditableTags(document: FlacDocument): TagValues {
        val comments = document.comments
        fun value(key: String) = comments?.values(key)?.firstOrNull().orEmpty()
        fun index(key: String, total: String): String {
            val number = value(key)
            return if (number.isNotBlank() && '/' !in number && value(total).isNotBlank()) "$number/${value(total)}" else number
        }
        return TagValues(mapOf(
            MetadataField.TITLE to value(FlacKeys.TITLE),
            MetadataField.ARTISTS to artistLines(comments?.values(FlacKeys.ARTIST).orEmpty()),
            MetadataField.ALBUM to value(FlacKeys.ALBUM), MetadataField.DATE to value(FlacKeys.DATE),
            MetadataField.TRACK to index(FlacKeys.TRACK, FlacKeys.TRACK_TOTAL),
            MetadataField.DISC to index(FlacKeys.DISC, FlacKeys.DISC_TOTAL),
            MetadataField.LYRICS to value(FlacKeys.LYRICS),
        ), hasCover = document.frontCover != null)
    }

    fun readTextMetadata(file: File): AudioTextMetadata = file.inputStream().use { readTextMetadata(it) }

    /** Text metadata needs comments only: skip other blocks and stop once comments are read. */
    fun readTextMetadata(input: InputStream, checkActive: () -> Unit = {}): AudioTextMetadata {
        val source = DataInputStream(input)
        require(source.readInt() == FLAC_MAGIC) { "Not a FLAC file" }
        var first = true
        while (true) {
            checkActive()
            val header = source.readUnsignedByte()
            val type = header and 0x7F
            val length = (source.readUnsignedByte() shl 16) or
                (source.readUnsignedByte() shl 8) or source.readUnsignedByte()
            if (first) {
                require(type == STREAM_INFO && length == 34) { "Missing or invalid STREAMINFO" }
                source.readFully(ByteArray(34))
                first = false
                if (header and 0x80 != 0) return textFromComments(null)
                continue
            }
            if (type == VORBIS_COMMENT) {
                val data = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    checkActive()
                    val count = minOf(8192, length - offset)
                    source.readFully(data, offset, count)
                    offset += count
                }
                return textFromComments(VorbisComments.decode(data))
            }
            // File-backed SAF streams can seek over pictures/padding; pipes are drained by skipNBytes.
            var remaining = length.toLong()
            while (remaining > 0) {
                checkActive()
                val count = minOf(64L * 1024, remaining)
                source.skipNBytes(count)
                remaining -= count
            }
            if (header and 0x80 != 0) return textFromComments(null)
        }
    }

    private fun textFromComments(comments: VorbisComments?): AudioTextMetadata {
        fun values(key: String) = comments?.values(key).orEmpty().filter(String::isNotBlank)
        return AudioTextMetadata.from(
            title = values(FlacKeys.TITLE).firstOrNull(), artists = values(FlacKeys.ARTIST),
            album = values(FlacKeys.ALBUM).firstOrNull(), disc = values(FlacKeys.DISC).firstOrNull(),
            track = values(FlacKeys.TRACK).firstOrNull(), date = values(FlacKeys.DATE).firstOrNull(),
            comment = (values(FlacKeys.COMMENT) + values(FlacKeys.DESCRIPTION)).firstOrNull(),
            albumArtists = values(FlacKeys.ALBUMARTIST).ifEmpty { values(FlacKeys.ALBUM_ARTIST) },
        )
    }

    fun read(file: File): FlacDocument {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readNBytes(4).contentEquals(FLAC_MAGIC_BYTES)) {
                "Not a FLAC file"
            }
            val blocks = mutableListOf<FlacBlock>()
            var last = false
            var offset = 4L
            while (!last) {
                val header = input.readUnsignedByteOrThrow()
                last = header and 0x80 != 0
                val type = header and 0x7F
                val length = (input.readUnsignedByteOrThrow() shl 16) or
                    (input.readUnsignedByteOrThrow() shl 8) or input.readUnsignedByteOrThrow()
                val data = input.readNBytes(length)
                require(data.size == length) { "Truncated FLAC metadata block" }
                blocks += FlacBlock(type, data)
                offset += 4L + length
            }
            require(blocks.firstOrNull()?.type == STREAM_INFO && blocks.first().data.size == 34) {
                "Missing or invalid STREAMINFO"
            }
            return FlacDocument(blocks, offset, durationFromStreamInfo(blocks.first().data))
        }
    }

    fun readLocalTrack(file: File): LocalTrack = readLocalTrack(file, read(file))

    internal fun readLocalTrack(file: File, document: FlacDocument): LocalTrack {
        val comments = document.comments
        return LocalTrack(
            fileName = file.name,
            title = comments?.values(FlacKeys.TITLE)?.firstOrNull(),
            artists = comments?.values(FlacKeys.ARTIST).orEmpty(),
            album = comments?.values(FlacKeys.ALBUM)?.firstOrNull(),
            durationMs = document.durationMs,
        )
    }

    fun frontCoverBytes(file: File): ByteArray? = read(file).frontCover?.bytes

    fun writeMetadata(output: File, blocks: List<FlacBlock>, source: File, audioOffset: Long) {
        require(blocks.isNotEmpty() && blocks.first().type == STREAM_INFO)
        require(blocks.all { it.type in 0..126 && it.data.size <= MAX_BLOCK_SIZE })
        output.outputStream().buffered().use { sink ->
            sink.write(FLAC_MAGIC_BYTES)
            blocks.forEachIndexed { index, block ->
                val lastFlag = if (index == blocks.lastIndex) 0x80 else 0
                sink.write(lastFlag or block.type)
                sink.write(block.data.size ushr 16)
                sink.write(block.data.size ushr 8)
                sink.write(block.data.size)
                sink.write(block.data)
            }
            RandomAccessFile(source, "r").use { sourceFile ->
                sourceFile.seek(audioOffset)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val count = sourceFile.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
            }
        }
    }

    private fun durationFromStreamInfo(data: ByteArray): Long? {
        val packed = ByteBuffer.wrap(data, 10, 8).order(ByteOrder.BIG_ENDIAN).long.toULong()
        val sampleRate = ((packed shr 44) and 0xF_FFFFuL).toLong()
        val totalSamples = (packed and 0xF_FFFF_FFFFuL).toLong()
        if (sampleRate == 0L || totalSamples == 0L) return null
        return (totalSamples.toDouble() * 1_000.0 / sampleRate).roundToLong()
    }
}

private fun DataInputStream.readUnsignedByteOrThrow(): Int = try {
    readUnsignedByte()
} catch (_: EOFException) {
    throw IllegalArgumentException("Truncated FLAC metadata header")
}

private fun ByteBuffer.readUtf8Le(): String {
    require(remaining() >= 4) { "Truncated Vorbis comment" }
    val length = int.requireNonNegative("Vorbis string length")
    require(length <= remaining()) { "Truncated Vorbis string" }
    return ByteArray(length).also(::get).toString(Charsets.UTF_8)
}

private fun ByteBuffer.readStringBe(charset: java.nio.charset.Charset): String {
    require(remaining() >= 4) { "Truncated FLAC picture string" }
    val length = int.requireNonNegative("Picture string length")
    require(length <= remaining()) { "Truncated FLAC picture string" }
    return ByteArray(length).also(::get).toString(charset)
}

private fun Int.requireNonNegative(label: String): Int {
    require(this >= 0) { "$label is invalid" }
    return this
}
