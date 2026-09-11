package top.michubil.musictag.data.wav

import top.michubil.musictag.data.model.map
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.textValues
import top.michubil.musictag.data.id3.Id3Frame
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.io.AtomicAudioFileRewriter.FileRegion
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.rename.AudioTextMetadata
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToLong

private const val RIFF_HEADER_SIZE = 12L
private const val CHUNK_HEADER_SIZE = 8L
private const val MAX_RIFF_SIZE = 0xFFFF_FFFFL
private const val ID3_CHUNK = "ID3 "
private const val LOWERCASE_ID3_CHUNK = "id3 "

data class WavChunk(
    val id: String,
    val headerOffset: Long,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val storedSize: Long
        get() = CHUNK_HEADER_SIZE + dataSize + (dataSize and 1L)
}

data class WavDocument(
    val chunks: List<WavChunk>,
    val id3Frames: List<Id3Frame>,
    val info: Map<String, String>,
    val infoChunkOffsets: Set<Long>,
    val durationMs: Long?,
) {
    val audioRegion: FileRegion
        get() = chunks.first { it.id == "data" }.let { FileRegion(it.dataOffset, it.dataSize) }
}

object WavCodec {
    fun readEditableTags(file: File): TagValues = readEditableTags(read(file))

    internal fun readEditableTags(document: WavDocument): TagValues {
        val id3 = Id3Codec.readEditableTags(document.id3Frames)
        return id3.copy(text = id3.text.mapValues { (field, value) ->
            document.resolveText(field, value).orEmpty()
        })
    }

    fun read(file: File): WavDocument = RandomAccessFile(file, "r").use { source ->
        require(source.length() >= RIFF_HEADER_SIZE) { "Truncated WAV header" }
        require(source.readFourCc() == "RIFF") { "Only standard RIFF WAV files are supported" }
        val riffSize = source.readUInt32Le()
        require(source.readFourCc() == "WAVE") { "Not a WAVE file" }
        require(riffSize + 8L == source.length()) { "Invalid RIFF size" }

        val chunks = mutableListOf<WavChunk>()
        while (source.filePointer < source.length()) {
            val headerOffset = source.filePointer
            require(source.length() - headerOffset >= CHUNK_HEADER_SIZE) { "Truncated WAV chunk header" }
            val id = source.readFourCc()
            val dataSize = source.readUInt32Le()
            val dataOffset = source.filePointer
            val storedSize = CHUNK_HEADER_SIZE + dataSize + (dataSize and 1L)
            require(headerOffset + storedSize <= source.length()) { "Truncated WAV chunk" }
            chunks += WavChunk(id, headerOffset, dataOffset, dataSize)
            source.seek(headerOffset + storedSize)
        }

        require(chunks.any { it.id == "fmt " }) { "WAV fmt chunk is missing" }
        val dataChunk = chunks.firstOrNull { it.id == "data" } ?: error("WAV data chunk is missing")
        val id3Chunk = chunks.firstOrNull { it.id == ID3_CHUNK || it.id == LOWERCASE_ID3_CHUNK }
        val frames = id3Chunk?.let { Id3Codec.readFrames(file, it.dataOffset, it.dataSize) }.orEmpty()
        val infoChunks = chunks.mapNotNull { chunk ->
            readInfo(source, chunk)?.let { chunk.headerOffset to it }
        }
        val info = linkedMapOf<String, String>()
        infoChunks.forEach { (_, values) ->
            values.forEach { (id, value) -> info.putIfAbsent(id, value) }
        }
        WavDocument(
            chunks = chunks,
            id3Frames = frames,
            info = info,
            infoChunkOffsets = infoChunks.mapTo(mutableSetOf()) { it.first },
            durationMs = pcmDurationMs(source, chunks, dataChunk),
        )
    }

    fun readLocalTrack(file: File): LocalTrack = readLocalTrack(file, read(file))

    internal fun readLocalTrack(file: File, document: WavDocument): LocalTrack {
        val id3Track = Id3Codec.readLocalTrack(file, document.id3Frames, document.durationMs)
        return id3Track.copy(
            title = document.resolveText(MetadataField.TITLE, id3Track.title),
            artists = id3Track.artists.filter(String::isNotBlank).ifEmpty { listOfNotNull(document.infoText(MetadataField.ARTISTS)) },
            album = document.resolveText(MetadataField.ALBUM, id3Track.album),
        )
    }

    fun readTextMetadata(file: File): AudioTextMetadata {
        val document = read(file)
        val info = document.info
        val fallback = AudioTextMetadata.from(
            title = document.infoText(MetadataField.TITLE), artists = listOfNotNull(document.infoText(MetadataField.ARTISTS)),
            album = document.infoText(MetadataField.ALBUM), track = document.infoText(MetadataField.TRACK),
            date = document.infoText(MetadataField.DATE), comment = info["ICMT"],
        )
        val id3 = Id3Codec.readTextMetadata(document.id3Frames)
        return AudioTextMetadata(
            values = fallback.values + id3.values,
            date = id3.date ?: fallback.date,
            artists = id3.artists.ifEmpty { fallback.artists },
            albumArtists = id3.albumArtists.ifEmpty { fallback.albumArtists },
        )
    }

    fun writeMetadata(
        output: File,
        source: File,
        document: WavDocument,
        frames: List<Id3Frame>,
        info: Map<String, String>,
        rewriteInfo: Boolean,
    ) {
        val keptChunks = document.chunks.filterNot { chunk ->
            chunk.id == ID3_CHUNK ||
                chunk.id == LOWERCASE_ID3_CHUNK ||
                rewriteInfo && chunk.headerOffset in document.infoChunkOffsets
        }
        val tag = frames.takeIf(List<Id3Frame>::isNotEmpty)?.let {
            Id3Codec.renderTag(it)
        }
        val infoData = if (rewriteInfo && info.isNotEmpty()) renderInfo(info) else null
        val tagStoredSize = tag?.let { CHUNK_HEADER_SIZE + it.size + (it.size and 1) } ?: 0L
        val infoStoredSize = infoData?.let { CHUNK_HEADER_SIZE + it.size + (it.size and 1) } ?: 0L
        val riffSize = 4L + keptChunks.sumOf(WavChunk::storedSize) + infoStoredSize + tagStoredSize
        require(riffSize <= MAX_RIFF_SIZE) { "WAV file is too large for standard RIFF" }

        output.outputStream().buffered().use { sink ->
            sink.writeFourCc("RIFF")
            sink.writeUInt32Le(riffSize)
            sink.writeFourCc("WAVE")
            RandomAccessFile(source, "r").use { sourceFile ->
                keptChunks.forEach { chunk ->
                    sourceFile.seek(chunk.headerOffset)
                    sourceFile.copyExactly(sink, chunk.storedSize)
                }
            }
            if (infoData != null) {
                sink.writeFourCc("LIST")
                sink.writeUInt32Le(infoData.size.toLong())
                sink.write(infoData)
                if (infoData.size and 1 != 0) sink.write(0)
            }
            if (tag != null) {
                sink.writeFourCc(ID3_CHUNK)
                sink.writeUInt32Le(tag.size.toLong())
                sink.write(tag)
                if (tag.size and 1 != 0) sink.write(0)
            }
        }
    }

    private fun renderInfo(values: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeFourCc("INFO")
            values.forEach { (id, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8) + 0
                output.writeFourCc(id)
                output.writeUInt32Le(bytes.size.toLong())
                output.write(bytes)
                if (bytes.size and 1 != 0) output.write(0)
            }
            output.toByteArray()
        }

    fun readDuration(input: InputStream): Long? {
        val source = DataInputStream(input)
        if (source.readFourCc() != "RIFF") return null
        source.skipFully(4)
        if (source.readFourCc() != "WAVE") return null
        var bytesPerSecond: Long? = null
        var dataSize: Long? = null
        while (bytesPerSecond == null || dataSize == null) {
            val id = try { source.readFourCc() } catch (_: EOFException) { break }
            val size = source.readUInt32Le()
            when (id) {
                "fmt " -> {
                    if (size < 12) return null
                    source.skipFully(8)
                    bytesPerSecond = source.readUInt32Le()
                    source.skipFully(size - 12)
                }
                "data" -> {
                    dataSize = size
                    if (bytesPerSecond != null) break
                    source.skipFully(size)
                }
                else -> source.skipFully(size)
            }
            if (size and 1L != 0L) source.skipFully(1)
        }
        return pcmDurationMs(dataSize ?: return null, bytesPerSecond ?: return null)
    }

    private fun pcmDurationMs(
        source: RandomAccessFile,
        chunks: List<WavChunk>,
        dataChunk: WavChunk,
    ): Long? {
        val format = chunks.first { it.id == "fmt " }
        if (format.dataSize < 12) return null
        source.seek(format.dataOffset + 8)
        return pcmDurationMs(dataChunk.dataSize, source.readUInt32Le())
    }

    private fun pcmDurationMs(dataSize: Long, bytesPerSecond: Long): Long? =
        if (bytesPerSecond == 0L) null else (dataSize.toDouble() * 1_000.0 / bytesPerSecond).roundToLong()

    private fun readInfo(source: RandomAccessFile, chunk: WavChunk): Map<String, String>? {
        if (chunk.id != "LIST" || chunk.dataSize < 4) return null
        source.seek(chunk.dataOffset)
        if (source.readFourCc() != "INFO") return null
        val result = linkedMapOf<String, String>()
        val end = chunk.dataOffset + chunk.dataSize
        while (source.filePointer < end) {
            require(end - source.filePointer >= CHUNK_HEADER_SIZE) { "Truncated WAV INFO field" }
            val id = source.readFourCc()
            val size = source.readUInt32Le()
            require(size <= end - source.filePointer) { "Truncated WAV INFO value" }
            require(size <= Int.MAX_VALUE) { "WAV INFO value is too large" }
            val bytes = ByteArray(size.toInt()).also(source::readFully)
            result.putIfAbsent(id, bytes.toString(Charsets.UTF_8).trimEnd('\u0000'))
            if (size and 1L != 0L) source.skipBytes(1)
        }
        return result
    }
}

private fun RandomAccessFile.readFourCc(): String =
    ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)

private fun RandomAccessFile.readUInt32Le(): Long =
    Integer.toUnsignedLong(Integer.reverseBytes(readInt()))

private fun DataInputStream.readFourCc(): String =
    ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)

private fun DataInputStream.readUInt32Le(): Long {
    val b0 = readUnsignedByte().toLong()
    val b1 = readUnsignedByte().toLong()
    val b2 = readUnsignedByte().toLong()
    val b3 = readUnsignedByte().toLong()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun DataInputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() < 0) throw EOFException()
            remaining--
        } else remaining -= skipped
    }
}

private fun RandomAccessFile.copyExactly(output: OutputStream, byteCount: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    var remaining = byteCount
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(count > 0) { "Truncated WAV chunk" }
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun OutputStream.writeFourCc(value: String) {
    require(value.length == 4)
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeUInt32Le(value: Long) {
    require(value in 0..MAX_RIFF_SIZE)
    write(value.toInt())
    write((value ushr 8).toInt())
    write((value ushr 16).toInt())
    write((value ushr 24).toInt())
}

internal val wavInfoIds: Map<MetadataField, List<String>> = mapOf(
    MetadataField.TITLE to listOf("INAM"),
    MetadataField.ARTISTS to listOf("IART"),
    MetadataField.ALBUM to listOf("IPRD"),
    MetadataField.DATE to listOf("ICRD"),
    MetadataField.TRACK to listOf("IPRT", "ITRK"),
)

internal fun WavDocument.infoText(field: MetadataField): String? =
    wavInfoIds[field].orEmpty().firstNotNullOfOrNull { info[it]?.takeIf(String::isNotBlank) }

internal fun WavDocument.resolveText(field: MetadataField, id3: String?): String? =
    id3?.takeIf(String::isNotBlank) ?: infoText(field)

internal fun ScrapedMetadata.wavTextValues(): Map<MetadataField, RemoteValue<String>> {
    val texts = textValues()
    return wavInfoIds.keys.associateWith { field ->
        texts.getValue(field).map { values ->
            if (field == MetadataField.ARTISTS) values.joinToString(" / ") else values.first()
        }
    }
}
