package top.michubil.musictag.data.match

import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.artistLines
import top.michubil.musictag.data.model.merge
import top.michubil.musictag.data.model.textValues
import top.michubil.musictag.data.model.shouldWrite
import top.michubil.musictag.data.model.shouldFetch
import top.michubil.musictag.data.model.value

internal val releaseFields = setOf(MetadataField.ALBUM, MetadataField.DATE, MetadataField.COVER, MetadataField.TRACK, MetadataField.DISC)

internal fun retainReason(
    field: MetadataField,
    evidence: Evidence?,
    release: SongCandidate?,
    manual: Boolean,
): String? {
    if (evidence == null) return "没有可靠的录音对应"
    return when (field) {
        MetadataField.ARTISTS if evidence.artist != ArtistRelation.MATCH && !manual -> "缺少角色依据，保留本地署名"
        MetadataField.LYRICS if evidence.version == VersionRelation.CONFLICT || evidence.version == VersionRelation.ONE_SIDED ->
            "版本未确认，保留原歌词"
        in releaseFields if release == null -> "发行待确认，保留原内容"
        else -> null
    }
}

internal fun timedLyricsRejected(lyrics: String, evidence: Evidence?): Boolean =
    LyricsCodec.parse(lyrics).any { it.startMs != null } && evidence?.duration != DurationRelation.CLOSE

internal data class WritePlan(val metadata: ScrapedMetadata, val disposition: ScrapeDisposition)

internal fun fieldsToFetch(
    options: ScrapeOptions,
    existingText: Map<MetadataField, String>,
    hasCover: Boolean,
    supported: Set<MetadataField>,
): ScrapeOptions = options.copy(policies = options.policies.mapValues { (field, policy) ->
    val present = if (field == MetadataField.COVER) hasCover else !existingText[field].isNullOrBlank()
    policy.copy(enabled = field in supported && policy.shouldFetch(present))
})

internal fun planWrite(
    metadata: ScrapedMetadata,
    existingText: Map<MetadataField, String>,
    hasCover: Boolean,
    options: ScrapeOptions,
    kept: Map<MetadataField, String>,
    unsupported: Set<MetadataField> = emptySet(),
): WritePlan {
    val selected = options.policies.filterValues { it.enabled }.keys - unsupported
    val unchangedText = metadata.textValues().filter { (field, incoming) ->
        when (incoming) {
            is RemoteValue.Available -> {
                val text = if (field == MetadataField.ARTISTS) artistLines(incoming.value) else incoming.value.single()
                text.trim() == existingText[field].orEmpty().trim()
            }
            else -> false
        }
    }.keys
    val changed = selected.filterTo(mutableSetOf()) { field ->
        val incoming = metadata.value(field)
        val present = if (field == MetadataField.COVER) hasCover else !existingText[field].isNullOrBlank()
        options.policies.getValue(field).shouldWrite(incoming, present) && field !in unchangedText &&
            (incoming != RemoteValue.ConfirmedAbsent || present)
    }
    val missing = selected.count { metadata.value(it) == RemoteValue.Unavailable }
    val disposition = when {
        changed.isNotEmpty() && missing == 0 -> ScrapeDisposition(ScrapeKind.COMPLETE)
        changed.isNotEmpty() -> ScrapeDisposition(ScrapeKind.PARTIAL, kept.values.firstOrNull())
        missing < selected.size -> ScrapeDisposition(ScrapeKind.UNCHANGED)
        else -> ScrapeDisposition(ScrapeKind.FAILED, kept.values.firstOrNull() ?: "所选字段无法写入")
    }
    return WritePlan(ScrapedMetadata().merge(metadata, changed), disposition)
}
