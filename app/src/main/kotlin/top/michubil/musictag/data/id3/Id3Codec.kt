package top.michubil.musictag.data.id3

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.model.artistLines
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.rename.AudioTextMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

private val ID3_IDENTIFIER = "ID3".toByteArray(Charsets.US_ASCII)
private const val HEADER_SIZE = 10
private const val FRAME_HEADER_SIZE = 10
private const val MAX_SYNCHSAFE_SIZE = 0x0FFF_FFFF
private const val EXTENDED_HEADER = 0x40
private const val UNSYNCHRONISATION = 0x80
private const val FOOTER = 0x10
internal const val FRONT_COVER_TYPE = 3

internal object Id3Ids {
    const val TITLE = "TIT2"
    const val ARTISTS = "TPE1"
    const val ALBUM = "TALB"
    const val TRACK = "TRCK"
    const val DISC = "TPOS"
    const val DATE = "TDRC"
    const val RELEASE_DATE = "TDRL"
    const val LYRICS = "USLT"
    const val COVER = "APIC"
    const val ALBUM_ARTISTS = "TPE2"
    const val COMMENT = "COMM"
}

internal val id3TextFrames: Map<MetadataField, String> = mapOf(
    MetadataField.TITLE to Id3Ids.TITLE,
    MetadataField.ARTISTS to Id3Ids.ARTISTS,
    MetadataField.ALBUM to Id3Ids.ALBUM,
    MetadataField.DATE to Id3Ids.DATE,
    MetadataField.TRACK to Id3Ids.TRACK,
    MetadataField.DISC to Id3Ids.DISC,
)

data class Id3Frame(
    val id: String,
    val flags: Int = 0,
    val data: ByteArray,
    val version: Mp3TagVersion = Mp3TagVersion.V24,
)

data class Id3Document(
    val frames: List<Id3Frame>,
    val audioOffset: Long,
    val version: Mp3TagVersion = Mp3TagVersion.V24,
)

private class Id3Picture(
    val type: Int,
    val bytes: ByteArray,
)

object Id3Codec {
    fun read(file: File): Id3Document = RandomAccessFile(file, "r").use { source ->
        read(source, 0, source.length())
    }

    internal fun readFrames(file: File, offset: Long, length: Long): List<Id3Frame> =
        RandomAccessFile(file, "r").use { source ->
            val document = read(source, offset, length)
            require(document.audioOffset > offset && document.version == Mp3TagVersion.V24) {
                "WAV ID3 chunk does not contain an ID3v2.4 tag"
            }
            document.frames
        }

    private fun read(source: RandomAccessFile, offset: Long, length: Long): Id3Document {
        require(offset >= 0 && length >= 0 && offset + length <= source.length()) {
            "Invalid ID3 region"
        }
        if (length < HEADER_SIZE) return Id3Document(emptyList(), offset)
        source.seek(offset)
        val header = ByteArray(HEADER_SIZE).also(source::readFully)
        if (!header.copyOfRange(0, 3).contentEquals(ID3_IDENTIFIER)) {
            return Id3Document(emptyList(), offset)
        }
        val version = Mp3TagVersion.entries.firstOrNull { it.major == header[3].toInt() }
        require(version != null && header[4].toInt() == 0) { "MP3 仅支持 ID3v2.3 / ID3v2.4" }
        val tagFlags = header[5].toInt() and 0xFF
        require(tagFlags and (if (version == Mp3TagVersion.V23) 0x1F else 0x0F) == 0) { "Invalid ID3 flags" }
        val tagSize = decodeSynchsafe(header, 6)
        val footerSize = if (version == Mp3TagVersion.V24 && tagFlags and FOOTER != 0) HEADER_SIZE else 0
        require(HEADER_SIZE.toLong() + tagSize + footerSize <= length) {
            "Truncated ID3 tag"
        }
        val storedBody = ByteArray(tagSize).also(source::readFully)
        val body = if (version == Mp3TagVersion.V23 && tagFlags and UNSYNCHRONISATION != 0)
            removeUnsynchronisation(storedBody) else storedBody
        var position = if (tagFlags and EXTENDED_HEADER != 0) {
            require(body.size >= 4) { "Truncated ID3 extended header" }
            if (version == Mp3TagVersion.V23) {
                val size = decodeInt32(body, 0)
                require(size in 6..(body.size - 4)) { "Invalid ID3v2.3 extended header" }
                size + 4
            } else decodeSynchsafe(body, 0).also { size ->
                require(size in 6..body.size) { "Invalid ID3v2.4 extended header" }
            }
        } else {
            0
        }
        val frames = mutableListOf<Id3Frame>()
        while (position + FRAME_HEADER_SIZE <= body.size && body[position].toInt() != 0) {
            val id = body.copyOfRange(position, position + 4).toString(Charsets.US_ASCII)
            require(id.all { it in 'A'..'Z' || it in '0'..'9' }) { "Invalid ID3 frame ID" }
            val frameSize = if (version == Mp3TagVersion.V23) decodeInt32(body, position + 4)
                else decodeSynchsafe(body, position + 4)
            require(frameSize <= body.size - position - FRAME_HEADER_SIZE) { "Truncated ID3 frame" }
            val frameEnd = position + FRAME_HEADER_SIZE + frameSize
            val flags = ((body[position + 8].toInt() and 0xFF) shl 8) or
                (body[position + 9].toInt() and 0xFF)
            require(flags and (if (version == Mp3TagVersion.V23) 0x1F1F else 0x8FB0) == 0) { "Invalid ID3 frame flags" }
            val data = body.copyOfRange(position + FRAME_HEADER_SIZE, frameEnd)
            val unsynchronised = version == Mp3TagVersion.V24 &&
                (tagFlags and UNSYNCHRONISATION != 0 || flags and 0x02 != 0)
            frames += Id3Frame(
                id = id,
                flags = if (unsynchronised) flags and 0xFFFD else flags,
                data = if (unsynchronised) removeUnsynchronisation(data) else data,
                version = version,
            )
            position = frameEnd
        }
        require((position until body.size).all { body[it].toInt() == 0 }) { "Invalid ID3 padding" }
        return Id3Document(frames, offset + HEADER_SIZE + tagSize + footerSize, version)
    }

    fun readLocalTrack(file: File): LocalTrack {
        val document = read(file)
        return readLocalTrack(file, document.frames, null)
    }

    fun readTextMetadata(file: File): AudioTextMetadata = readTextMetadata(read(file).frames)

    fun readEditableTags(file: File): TagValues = readEditableTags(read(file).frames)

    internal fun readEditableTags(frames: List<Id3Frame>): TagValues = TagValues(
        mapOf(
            MetadataField.TITLE to frames.firstText(Id3Ids.TITLE).orEmpty(),
            MetadataField.ARTISTS to artistLines(frames.firstTextValues(Id3Ids.ARTISTS)),
            MetadataField.ALBUM to frames.firstText(Id3Ids.ALBUM).orEmpty(),
            MetadataField.DATE to recordingDate(frames).orEmpty(),
            MetadataField.TRACK to frames.firstText(Id3Ids.TRACK).orEmpty(),
            MetadataField.DISC to frames.firstText(Id3Ids.DISC).orEmpty(),
            MetadataField.LYRICS to frames.asSequence().filter { it.id == Id3Ids.LYRICS }.mapNotNull(::decodeComment).firstOrNull().orEmpty(),
        ),
        hasCover = frames.any(::isFrontCover),
    )

    internal fun readTextMetadata(frames: List<Id3Frame>): AudioTextMetadata = AudioTextMetadata.from(
        title = frames.firstText(Id3Ids.TITLE),
        artists = frames.firstTextValues(Id3Ids.ARTISTS),
        album = frames.firstText(Id3Ids.ALBUM),
        disc = frames.firstText(Id3Ids.DISC),
        track = frames.firstText(Id3Ids.TRACK),
        date = recordingDate(frames) ?: frames.firstText(Id3Ids.RELEASE_DATE),
        comment = frames.asSequence().filter { it.id == Id3Ids.COMMENT }.mapNotNull(::decodeComment).firstOrNull(),
        albumArtists = frames.firstTextValues(Id3Ids.ALBUM_ARTISTS),
    )

    private fun decodeComment(frame: Id3Frame): String? {
        val data = frame.readablePayload() ?: return null
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (data[4].toInt() and 255 == 255 && data.getOrNull(5)?.toInt()?.and(255) == 254)
                Charsets.UTF_16LE else Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        // COMM contains a language and a terminated descriptor before the actual comment.
        val start = if (encoding == 1 || encoding == 2) data.indexOfUtf16Terminator(4)
            else data.indexOf(0, 4).let { if (it < 0) -1 else it + 1 }
        if (start < 0 || start >= data.size) return null
        val textCharset = if (encoding == 1 && start + 1 < data.size &&
            ((data[start].toInt() and 255 == 254 && data[start + 1].toInt() and 255 == 255) ||
                (data[start].toInt() and 255 == 255 && data[start + 1].toInt() and 255 == 254))) Charsets.UTF_16 else charset
        return data.copyOfRange(start, data.size).toString(textCharset).removePrefix("\uFEFF")
            .trimEnd('\u0000').takeIf(String::isNotBlank)
    }

    fun frontCoverBytes(file: File): ByteArray? = read(file).frames.asSequence()
        .frontCoverBytes()

    /** Display unclassified artwork when needed; editing still preserves non-front pictures. */
    fun artworkCandidates(file: File): Sequence<ByteArray> = artworkCandidates(read(file).frames)

    internal fun artworkCandidates(frames: List<Id3Frame>): Sequence<ByteArray> = frames.asSequence()
        .filter { it.id == Id3Ids.COVER }
        .mapNotNull(::decodePicture)
        .filter { it.bytes.isNotEmpty() && (it.type == FRONT_COVER_TYPE || it.type == 0) }
        .sortedBy { if (it.type == FRONT_COVER_TYPE) 0 else 1 }
        .map { it.bytes }

    internal fun readLocalTrack(
        file: File,
        frames: List<Id3Frame>,
        durationMs: Long?,
    ) = LocalTrack(
        fileName = file.name,
        title = frames.firstText(Id3Ids.TITLE),
        artists = frames.firstTextValues(Id3Ids.ARTISTS),
        album = frames.firstText(Id3Ids.ALBUM),
        durationMs = durationMs,
    )

    internal fun frontCoverBytes(frames: List<Id3Frame>): ByteArray? = frames.asSequence()
        .frontCoverBytes()

    private fun Sequence<Id3Frame>.frontCoverBytes(): ByteArray? = this
        .filter { it.id == Id3Ids.COVER }
        .mapNotNull(::decodePicture)
        .firstOrNull { it.type == FRONT_COVER_TYPE }
        ?.bytes

    fun writeTag(
        output: File,
        frames: List<Id3Frame>,
        source: File,
        audioOffset: Long,
        paddingSize: Int = 1_024,
        version: Mp3TagVersion = Mp3TagVersion.V24,
    ) {
        val tag = renderTag(frames, paddingSize, version)
        output.outputStream().buffered().use { sink ->
            sink.write(tag)
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

    internal fun renderTag(
        frames: List<Id3Frame>,
        paddingSize: Int = 1_024,
        version: Mp3TagVersion = Mp3TagVersion.V24,
    ): ByteArray {
        require(paddingSize >= 0)
        val converted = convertId3Frames(frames, version)
        val bodySize = converted.sumOf { FRAME_HEADER_SIZE.toLong() + it.data.size } + paddingSize
        require(bodySize <= MAX_SYNCHSAFE_SIZE) { "ID3 tag is too large" }
        val body = ByteArrayOutputStream(bodySize.toInt()).use { sink ->
            converted.forEach { frame ->
                require(frame.id.length == 4 && frame.id.all { it in 'A'..'Z' || it in '0'..'9' })
                require(frame.data.size <= MAX_SYNCHSAFE_SIZE)
                sink.write(frame.id.toByteArray(Charsets.US_ASCII))
                sink.write(if (version == Mp3TagVersion.V23) encodeInt32(frame.data.size) else encodeSynchsafe(frame.data.size))
                sink.write(frame.flags ushr 8)
                sink.write(frame.flags)
                sink.write(frame.data)
            }
            sink.write(ByteArray(paddingSize))
            sink.toByteArray()
        }
        // 2.3 inserts escape bytes after measuring frames; 2.4 measures escaped frame payloads.
        val storedBody = if (version == Mp3TagVersion.V23) addUnsynchronisation(body) else body
        require(storedBody.size <= MAX_SYNCHSAFE_SIZE) { "ID3 tag is too large" }
        return ByteArrayOutputStream(HEADER_SIZE + storedBody.size).use { sink ->
            sink.write(ID3_IDENTIFIER)
            sink.write(version.major)
            sink.write(0)
            sink.write(if (storedBody.size != body.size) UNSYNCHRONISATION else 0)
            sink.write(encodeSynchsafe(storedBody.size))
            sink.write(storedBody)
            sink.toByteArray()
        }
    }

    internal fun textFrame(id: String, values: List<String>): Id3Frame? {
        val cleaned = values.filter(String::isNotBlank)
        if (cleaned.isEmpty()) return null
        val text = cleaned.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return Id3Frame(id, data = byteArrayOf(3) + text)
    }

    internal fun lyricsFrame(value: String): Id3Frame? {
        if (value.isBlank()) return null
        return Id3Frame(
            id = Id3Ids.LYRICS,
            data = byteArrayOf(3) + "eng".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
                value.toByteArray(Charsets.UTF_8),
        )
    }

    internal fun frontCoverFrame(mimeType: String, bytes: ByteArray): Id3Frame = Id3Frame(
        id = Id3Ids.COVER,
        data = byteArrayOf(3) + mimeType.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf(FRONT_COVER_TYPE.toByte(), 0) + bytes,
    )

    internal fun isFrontCover(frame: Id3Frame): Boolean = frame.id == Id3Ids.COVER &&
        decodePicture(frame)?.type == FRONT_COVER_TYPE

    internal fun framesContentEquals(first: List<Id3Frame>, second: List<Id3Frame>): Boolean =
        first.size == second.size && first.indices.all { index ->
            first[index].id == second[index].id &&
                first[index].flags == second[index].flags &&
                first[index].version == second[index].version &&
                first[index].data.contentEquals(second[index].data)
        }

    private fun List<Id3Frame>.firstText(id: String): String? = firstTextValues(id).firstOrNull()

    private fun List<Id3Frame>.firstTextValues(id: String): List<String> = firstOrNull {
        it.id == id && it.readablePayload() != null
    }?.let { personValues(it, decodeText(it)) }.orEmpty()

    internal fun decodeText(frame: Id3Frame): List<String> {
        val data = frame.readablePayload() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        val charset = when (data[0].toInt() and 0xFF) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return emptyList()
        }
        return data.copyOfRange(1, data.size).toString(charset)
            .trimEnd('\u0000')
            .split('\u0000')
            .map { it.removePrefix("\uFEFF") }
            .filter(String::isNotBlank)
    }

    private fun decodePicture(frame: Id3Frame): Id3Picture? {
        val data = frame.readablePayload() ?: return null
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        if (encoding !in 0..3) return null
        val mimeEnd = data.indexOf(0, 1)
        if (mimeEnd < 0 || mimeEnd + 2 >= data.size) return null
        val type = data[mimeEnd + 1].toInt() and 0xFF
        val descriptionStart = mimeEnd + 2
        val imageStart = if (encoding == 1 || encoding == 2) {
            data.indexOfUtf16Terminator(descriptionStart)
        } else {
            data.indexOf(0, descriptionStart).let { if (it < 0) -1 else it + 1 }
        }
        if (imageStart < 0 || imageStart > data.size) return null
        return Id3Picture(type, data.copyOfRange(imageStart, data.size))
    }
}

internal fun leadingId3TagSize(header: ByteArray): Int? {
    if (header.size < HEADER_SIZE || !header.copyOfRange(0, 3).contentEquals(ID3_IDENTIFIER)) {
        return null
    }
    require(header[3].toInt() in 3..4 && header[4].toInt() == 0) { "MP3 仅支持 ID3v2.3 / ID3v2.4" }
    val tagSize = decodeSynchsafe(header, 6)
    val footer = if (header[3].toInt() == 4 && header[5].toInt() and FOOTER != 0) HEADER_SIZE else 0
    return HEADER_SIZE + tagSize + footer
}

private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset + 4 <= bytes.size)
    val values = IntArray(4) { index -> bytes[offset + index].toInt() and 0xFF }
    require(values.all { it and 0x80 == 0 }) { "Invalid synchsafe integer" }
    return (values[0] shl 21) or (values[1] shl 14) or (values[2] shl 7) or values[3]
}

private fun encodeSynchsafe(value: Int): ByteArray {
    require(value in 0..MAX_SYNCHSAFE_SIZE)
    return byteArrayOf(
        (value ushr 21).toByte(),
        (value ushr 14 and 0x7F).toByte(),
        (value ushr 7 and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}

private fun ByteArray.indexOf(value: Int, start: Int): Int {
    for (index in start until size) if (this[index].toInt() and 0xFF == value) return index
    return -1
}

private fun ByteArray.indexOfUtf16Terminator(start: Int): Int {
    var index = start
    while (index + 1 < size) {
        if (this[index].toInt() == 0 && this[index + 1].toInt() == 0) return index + 2
        index += 2
    }
    return -1
}
