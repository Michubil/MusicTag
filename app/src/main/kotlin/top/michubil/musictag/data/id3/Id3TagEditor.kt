package top.michubil.musictag.data.id3

import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.writeChange
import top.michubil.musictag.data.model.textValues

internal object Id3TagEditor {
    fun mutateFrames(
        original: List<Id3Frame>,
        metadata: ScrapedMetadata,
        options: ScrapeOptions,
    ): List<Id3Frame> {
        val replacements = linkedMapOf<String, List<Id3Frame>>()
        val texts = metadata.textValues()
        id3TextFrames.forEach { (field, id) ->
            putText(replacements, id, original, texts.getValue(field), options.policies.getValue(field))
        }
        putLyrics(replacements, original, metadata.lyrics, options.policies.getValue(MetadataField.LYRICS))

        val coverReplacement = coverFrames(original, metadata.cover, options.policies.getValue(MetadataField.COVER))

        val result = mutableListOf<Id3Frame>()
        val inserted = mutableSetOf<String>()
        var coverInserted = false
        for (frame in original) {
            val replacementId = if (isRecordingDateFrame(frame)) Id3Ids.DATE else frame.id
            val replacement = replacements[replacementId]
            when {
                replacement != null -> if (inserted.add(replacementId)) result += replacement
                coverReplacement != null && Id3Codec.isFrontCover(frame) -> if (!coverInserted) {
                    result += coverReplacement
                    coverInserted = true
                }
                else -> result += frame
            }
        }
        replacements.forEach { (id, replacement) ->
            if (inserted.add(id)) result += replacement
        }
        if (coverReplacement != null && !coverInserted) result += coverReplacement
        return if (Id3Codec.framesContentEquals(result, original)) original else result
    }

    private fun putText(
        target: MutableMap<String, List<Id3Frame>>,
        id: String,
        existing: List<Id3Frame>,
        incoming: RemoteValue<List<String>>,
        policy: FieldPolicy,
    ) {
        val hasExisting = existing.any { if (id == Id3Ids.DATE) isRecordingDateFrame(it) else it.id == id }
        policy.writeChange(incoming, hasExisting, write = { target[id] = listOfNotNull(Id3Codec.textFrame(id, it)) },
            clear = { target[id] = emptyList() })
    }

    private fun coverFrames(
        original: List<Id3Frame>,
        incoming: RemoteValue<CoverImage>,
        policy: FieldPolicy,
    ): List<Id3Frame>? {
        var replacement: List<Id3Frame>? = null
        policy.writeChange(incoming, original.any(Id3Codec::isFrontCover),
            write = { replacement = listOf(Id3Codec.frontCoverFrame(it.mimeType, it.bytes)) },
            clear = { replacement = emptyList() })
        return replacement
    }

    private fun putLyrics(
        target: MutableMap<String, List<Id3Frame>>,
        existing: List<Id3Frame>,
        incoming: RemoteValue<String>,
        policy: FieldPolicy,
    ) {
        policy.writeChange(incoming, existing.any { it.id == Id3Ids.LYRICS },
            write = { target[Id3Ids.LYRICS] = listOfNotNull(Id3Codec.lyricsFrame(it)) },
            clear = { target[Id3Ids.LYRICS] = emptyList() })
    }
}
