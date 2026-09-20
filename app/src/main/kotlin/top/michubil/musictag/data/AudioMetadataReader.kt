package top.michubil.musictag.data

import java.io.File
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.flac.FlacCodec
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.wav.WavCodec

internal object AudioMetadataReader {
    data class Preview(val track: Result<LocalTrack>, val pictures: Result<Sequence<ByteArray>>)
    data class Editor(val tags: TagValues, val pictures: Result<Sequence<ByteArray>>)

    fun readTrack(file: File): LocalTrack = parse(file).track()

    fun readPreview(file: File): Preview = parse(file).let {
        Preview(runCatching(it.track), runCatching(it.pictures))
    }

    fun readEditor(file: File, includeArtwork: Boolean): Editor = parse(file).let {
        Editor(it.tags(), runCatching { if (includeArtwork) it.pictures() else emptySequence() })
    }

    // Each extractor closes over the same parsed document; unused data is never extracted.
    private class Parsed(
        val track: () -> LocalTrack,
        val tags: () -> TagValues,
        val pictures: () -> Sequence<ByteArray>,
    )

    private fun parse(file: File): Parsed = when (file.extension) {
        "flac" -> {
            val document = FlacCodec.read(file)
            Parsed({ FlacCodec.readLocalTrack(file, document) }, { FlacCodec.readEditableTags(document) },
                { listOfNotNull(document.frontCover?.bytes).asSequence() })
        }
        "mp3" -> {
            val document = Id3Codec.read(file)
            Parsed({ Id3Codec.readLocalTrack(file, document.frames, null) }, { Id3Codec.readEditableTags(document.frames) },
                { Id3Codec.artworkCandidates(document.frames) })
        }
        "wav" -> {
            val document = WavCodec.read(file)
            Parsed({ WavCodec.readLocalTrack(file, document) }, { WavCodec.readEditableTags(document) },
                { listOfNotNull(Id3Codec.frontCoverBytes(document.id3Frames)).asSequence() })
        }
        else -> error("不支持的音频格式")
    }
}
