package top.michubil.musictag.data.id3

import top.michubil.musictag.data.model.Mp3TagVersion
import java.io.ByteArrayOutputStream

internal fun decodeInt32(bytes: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset + 4 <= bytes.size)
    val value = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
    require(value <= Int.MAX_VALUE) { "ID3 frame is too large" }
    return value.toInt()
}

internal fun removeUnsynchronisation(bytes: ByteArray): ByteArray = ByteArrayOutputStream(bytes.size).use { out ->
    var index = 0
    while (index < bytes.size) {
        val value = bytes[index++].toInt() and 255
        out.write(value)
        if (value == 255 && index < bytes.size && bytes[index].toInt() == 0) index++
    }
    out.toByteArray()
}

/** Compression/encryption remain opaque. Group IDs and data-length indicators precede the payload. */
internal fun Id3Frame.readablePayload(): ByteArray? {
    if (flags and (if (version == Mp3TagVersion.V23) 0xC0 else 0x0C) != 0) return null
    val grouped = flags and (if (version == Mp3TagVersion.V23) 0x20 else 0x40) != 0
    val offset = (if (grouped) 1 else 0) + (if (version == Mp3TagVersion.V24 && flags and 1 != 0) 4 else 0)
    if (offset > data.size) return null
    return if (offset == 0) data else data.copyOfRange(offset, data.size)
}

private val recordingDateIds = setOf("TDRC", "TYER", "TDAT", "TIME")

internal fun isRecordingDateFrame(frame: Id3Frame): Boolean = frame.id in recordingDateIds ||
    (frame.id == "TXXX" && Id3Codec.decodeText(frame).firstOrNull() == "DATE")

internal fun recordingDate(frames: List<Id3Frame>): String? {
    fun text(id: String) = frames.firstOrNull { it.id == id }?.let(Id3Codec::decodeText)?.firstOrNull()
    text("TDRC")?.let { return it }
    frames.firstOrNull { it.id == "TXXX" && isRecordingDateFrame(it) }?.let {
        Id3Codec.decodeText(it).getOrNull(1)?.let { value -> return value }
    }
    val year = text("TYER") ?: return null
    val date = text("TDAT")?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    val time = text("TIME")?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    return year + (date?.let { "-${it.substring(2)}-${it.substring(0, 2)}" }.orEmpty()) +
        (if (date != null) time?.let { "T${it.substring(0, 2)}:${it.substring(2)}" }.orEmpty() else "")
}

/** Upgrade supported v2.3 frames without silently discarding fields. */
internal fun convertId3Frames(frames: List<Id3Frame>): List<Id3Frame> {
    if (frames.all { it.version == Mp3TagVersion.V24 }) return frames
    val dates = frames.filter(::isRecordingDateFrame)
    val convertDates = dates.any { it.version == Mp3TagVersion.V23 }
    val result = mutableListOf<Id3Frame>()
    var dateInserted = false
    for (frame in frames) {
        if (convertDates && isRecordingDateFrame(frame)) {
            if (!dateInserted) {
                require(dates.all { it.flags == 0 }) { "日期帧包含特殊标记，无法安全升级标签版本" }
                require(dates.groupBy { it.id }.values.all { it.size == 1 }) { "存在重复日期帧，无法安全升级标签版本" }
                val value = recordingDate(dates)
                require(value != null) { "日期标签不完整，无法安全升级标签版本" }
                require(dates.all { date -> dateMatches(date, value) }) { "日期标签冲突或格式异常，无法安全升级标签版本" }
                result += requireNotNull(Id3Codec.textFrame("TDRC", listOf(value)))
                dateInserted = true
            }
        } else result += convertFrame(frame)
    }
    return result
}

private fun dateMatches(frame: Id3Frame, value: String): Boolean {
    val values = Id3Codec.decodeText(frame)
    return when (frame.id) {
        "TDRC" -> values == listOf(value)
        "TXXX" -> values == listOf("DATE", value)
        "TYER" -> value.length >= 4 && values == listOf(value.take(4)) && value.take(4).all(Char::isDigit)
        "TDAT" -> value.length >= 10 && values == listOf(value.substring(8, 10) + value.substring(5, 7))
        "TIME" -> value.length >= 16 && values == listOf(value.substring(11, 13) + value.substring(14, 16))
        else -> false
    }
}

private fun convertFrame(frame: Id3Frame): Id3Frame {
    if (frame.version == Mp3TagVersion.V24) return frame
    val payload = frame.readablePayload()
    require(payload != null) { "${frame.id} 帧已压缩或加密，无法安全升级标签版本" }
    require(frame.id !in setOf("EQUA", "RVAD", "TRDA", "TSIZ")) {
        "${frame.id} 帧无法无损升级为 ID3v2.4"
    }
    val restoredId = if (frame.id == "TXXX") {
        val values = Id3Codec.decodeText(frame)
        v24TextDescriptions.entries.firstOrNull { it.value == values.firstOrNull() }?.key
    } else null
    val id = restoredId ?: when (frame.id) {
        "IPLS" -> "TIPL"
        "TORY" -> "TDOR"
        else -> frame.id
    }
    val grouped = frame.flags and 0x20 != 0
    val flags = ((frame.flags and 0xE000) ushr 1) or (if (grouped) 0x40 else 0)
    val converted = if (restoredId != null) {
        byteArrayOf(3) + Id3Codec.decodeText(frame).drop(1).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    } else convertPayload(frame.id, payload)
    return Id3Frame(id, flags, (if (grouped) byteArrayOf(frame.data[0]) else byteArrayOf()) + converted)
}

private val personFrames = setOf("TPE1", "TPE2", "TPE3", "TPE4", "TCOM", "TEXT", "TOLY", "TOPE")

// 2.4-only text fields stored as named user text in 2.3 are restored when upgrading.
private val v24TextDescriptions = mapOf(
    "TDEN" to "ENCODINGTIME", "TDRL" to "RELEASETIME", "TDTG" to "TAGGINGTIME",
    "TMOO" to "MOOD", "TPRO" to "PRODUCEDNOTICE", "TSOA" to "ALBUMSORT",
    "TSOP" to "ARTISTSORT", "TSOT" to "TITLESORT", "TSST" to "DISCSUBTITLE",
)

internal fun personValues(frame: Id3Frame, values: List<String>): List<String> =
    if (frame.version == Mp3TagVersion.V23 && frame.id in personFrames)
        values.flatMap { it.split('/') }.map(String::trim).filter(String::isNotBlank) else values

private fun convertPayload(id: String, data: ByteArray): ByteArray {
    val encoded = id.startsWith("T") || id in setOf("IPLS", "APIC", "COMM", "USLT", "WXXX", "GEOB", "USER", "SYLT", "OWNE", "COMR")
    if (!encoded) {
        require(id !in setOf("CHAP", "CTOC")) { "$id 帧包含嵌套标签，无法安全升级标签版本" }
        return data
    }
    require(data.isNotEmpty() && data[0].toInt() in 0..3) { "Invalid $id text encoding" }
    if (id in setOf("SYLT", "OWNE", "COMR")) {
        require(data[0].toInt() in 0..1) { "$id 的文字编码无法安全升级" }
        return data
    }
    val encoding = data[0].toInt()
    var position = 1
    var charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    fun decode(start: Int, end: Int): String {
        if (encoding == 1 && start + 1 < end) {
            val first = data[start].toInt() and 255
            val second = data[start + 1].toInt() and 255
            if (first == 255 && second == 254) charset = Charsets.UTF_16LE
            if (first == 254 && second == 255) charset = Charsets.UTF_16BE
        }
        return data.copyOfRange(start, end).toString(charset).removePrefix("\uFEFF")
    }
    fun terminated(): String {
        val start = position
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        while (position + width <= data.size) {
            if ((0 until width).all { data[position + it].toInt() == 0 }) {
                val text = decode(start, position)
                position += width
                return text
            }
            position += width
        }
        error("Unterminated $id descriptor")
    }
    return ByteArrayOutputStream().use { out ->
        out.write(3)
        fun writeText(text: String, terminated: Boolean = false) {
            out.write(text.toByteArray(Charsets.UTF_8))
            if (terminated) out.write(0)
        }
        fun copyPrefix(count: Int) {
            require(position + count <= data.size) { "Truncated $id frame" }
            out.write(data, position, count)
            position += count
        }
        fun copyMime() {
            val start = position
            while (position < data.size && data[position].toInt() != 0) position++
            require(position < data.size) { "Unterminated $id MIME type" }
            position++
            out.write(data, start, position - start)
        }
        when (id) {
            "APIC" -> { copyMime(); copyPrefix(1); writeText(terminated(), true); out.write(data, position, data.size - position) }
            "COMM", "USLT" -> { copyPrefix(3); writeText(terminated(), true); writeText(decode(position, data.size)) }
            "TXXX" -> { writeText(terminated(), true); writeText(decode(position, data.size)) }
            "WXXX" -> { writeText(terminated(), true); out.write(data, position, data.size - position) }
            "GEOB" -> { copyMime(); writeText(terminated(), true); writeText(terminated(), true); out.write(data, position, data.size - position) }
            "USER" -> { copyPrefix(3); writeText(decode(position, data.size)) }
            else -> {
                val text = decode(position, data.size).trimEnd('\u0000')
                val values = personValues(Id3Frame(id, data = data, version = Mp3TagVersion.V23), text.split('\u0000'))
                writeText(values.joinToString("\u0000"))
            }
        }
        out.toByteArray()
    }
}
