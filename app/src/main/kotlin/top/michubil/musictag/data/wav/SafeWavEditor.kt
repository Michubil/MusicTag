package top.michubil.musictag.data.wav

import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.id3.Id3TagEditor
import top.michubil.musictag.data.io.AtomicAudioFileRewriter
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.writeChange
import java.io.File

class SafeWavEditor {
    fun update(file: File, metadata: ScrapedMetadata, options: ScrapeOptions) {
        val source = WavCodec.read(file)
        val existing = WavCodec.readEditableTags(source)
        val frames = Id3TagEditor.mutateFrames(
            source.id3Frames,
            metadata,
            id3Options(existing, metadata, options),
        )
        val info = source.mutateInfo(existing, metadata, options)
        val rewriteInfo = info != source.info
        if (Id3Codec.framesContentEquals(frames, source.id3Frames) && !rewriteInfo) return

        AtomicAudioFileRewriter.replacePreservingRegion(
            source = file,
            sourceRegion = source.audioRegion,
            writeTemporary = { temporary ->
                WavCodec.writeMetadata(
                    output = temporary,
                    source = file,
                    document = source,
                    frames = frames,
                    info = info,
                    rewriteInfo = rewriteInfo,
                )
            },
            validateTemporary = { temporary ->
                val written = WavCodec.read(temporary)
                require(Id3Codec.framesContentEquals(written.id3Frames, frames)) {
                    "WAV ID3v2.4 metadata validation failed"
                }
                require(!rewriteInfo || written.info == info) { "WAV INFO metadata validation failed" }
                written.audioRegion
            },
        )
    }
}

private fun id3Options(existing: TagValues, metadata: ScrapedMetadata, options: ScrapeOptions): ScrapeOptions {
    val incoming = metadata.wavTextValues()
    return options.copy(policies = options.policies.mapValues { (field, policy) ->
        if (!policy.enabled || policy.overwrite || field !in wavInfoIds) policy
        else if (!existing.text[field].isNullOrBlank()) policy.copy(enabled = false)
        // Blank ID3 frames count as missing, but only available values may replace them.
        else policy.copy(enabled = incoming[field] is RemoteValue.Available, overwrite = true)
    })
}

private fun WavDocument.mutateInfo(
    existing: TagValues,
    metadata: ScrapedMetadata,
    options: ScrapeOptions,
): Map<String, String> {
    if (infoChunkOffsets.isEmpty()) return info
    val result = LinkedHashMap(info)
    metadata.wavTextValues().forEach { (field, incoming) ->
        val ids = wavInfoIds.getValue(field)
        options.policies.getValue(field).writeChange(incoming, !existing.text[field].isNullOrBlank(),
            write = { value ->
                result[ids.first()] = value
                ids.drop(1).filter(info::containsKey).forEach { result[it] = value }
            },
            clear = { ids.forEach(result::remove) })
    }
    return result
}
