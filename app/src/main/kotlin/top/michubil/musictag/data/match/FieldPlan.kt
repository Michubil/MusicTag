package top.michubil.musictag.data.match

import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.ReleaseDate
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.TrackIndex
import top.michubil.musictag.data.model.artistLines
import top.michubil.musictag.data.model.shouldWrite
import top.michubil.musictag.data.model.value

internal val releaseFields = setOf(MetadataField.ALBUM, MetadataField.DATE, MetadataField.COVER, MetadataField.TRACK, MetadataField.DISC)

internal fun retainReason(
    field: MetadataField,
    evidence: Evidence?,
    release: SongCandidate?,
    manual: Boolean,
): String? {
    if (evidence == null) return "没有可靠的录音对应"
    if (field == MetadataField.ARTISTS && evidence.artist != ArtistRelation.MATCH && !manual) {
        return "缺少角色依据，保留本地署名"
    }
    if (field == MetadataField.LYRICS &&
        (evidence.version == VersionRelation.CONFLICT || evidence.version == VersionRelation.ONE_SIDED)) {
        return "版本未确认，保留原歌词"
    }
    if (field in releaseFields && release == null) return "发行待确认，保留原内容"
    return null
}

internal fun timedLyricsRejected(lyrics: String, evidence: Evidence?): Boolean =
    LyricsCodec.parse(lyrics).any { it.startMs != null } && evidence?.duration != DurationRelation.CLOSE

internal data class WritePlan(val metadata: ScrapedMetadata, val disposition: ScrapeDisposition)

internal fun planWrite(
    metadata: ScrapedMetadata,
    existingText: Map<MetadataField, String>,
    hasCover: Boolean,
    options: ScrapeOptions,
    kept: Map<MetadataField, String>,
): WritePlan {
    val selected = options.policies.filterValues { it.enabled }.keys
    val writable = ScrapedMetadata(
        title = keepChanged(MetadataField.TITLE, metadata.title, existingText, hasCover, options),
        artists = keepChanged(MetadataField.ARTISTS, metadata.artists, existingText, hasCover, options),
        album = keepChanged(MetadataField.ALBUM, metadata.album, existingText, hasCover, options),
        date = keepChanged(MetadataField.DATE, metadata.date, existingText, hasCover, options),
        track = keepChanged(MetadataField.TRACK, metadata.track, existingText, hasCover, options),
        disc = keepChanged(MetadataField.DISC, metadata.disc, existingText, hasCover, options),
        lyrics = keepChanged(MetadataField.LYRICS, metadata.lyrics, existingText, hasCover, options),
        cover = keepChanged(MetadataField.COVER, metadata.cover, existingText, hasCover, options),
    )
    var same = 0
    var writes = 0
    var retained = 0
    for (field in selected) {
        when {
            writable.value(field) != RemoteValue.Unavailable -> writes++
            metadata.value(field) != RemoteValue.Unavailable -> same++
            else -> retained++
        }
    }
    val disposition = when {
        writes > 0 && retained == 0 -> ScrapeDisposition(ScrapeKind.COMPLETE)
        writes > 0 -> ScrapeDisposition(ScrapeKind.PARTIAL, kept.values.firstOrNull())
        same > 0 && retained == 0 -> ScrapeDisposition(ScrapeKind.UNCHANGED)
        else -> ScrapeDisposition(ScrapeKind.REVIEW, kept.values.firstOrNull() ?: "所选字段无法写入")
    }
    return WritePlan(writable, disposition)
}

private fun <T> keepChanged(
    field: MetadataField,
    incoming: RemoteValue<T>,
    existingText: Map<MetadataField, String>,
    hasCover: Boolean,
    options: ScrapeOptions,
): RemoteValue<T> {
    val policy = options.policies[field] ?: return RemoteValue.Unavailable
    if (!policy.enabled || incoming is RemoteValue.Unavailable) return RemoteValue.Unavailable
    val existing = existingText[field].orEmpty()
    val hasExisting = if (field == MetadataField.COVER) hasCover else existing.isNotBlank()
    if (!policy.shouldWrite(incoming, hasExisting)) return RemoteValue.Unavailable
    if (incoming is RemoteValue.Available && field != MetadataField.COVER && sameText(field, incoming.value, existing)) {
        return RemoteValue.Unavailable
    }
    if (incoming is RemoteValue.ConfirmedAbsent && !hasExisting) return RemoteValue.Unavailable
    return incoming
}

private fun sameText(field: MetadataField, value: Any?, existing: String): Boolean {
    val names = (value as? List<*>)?.mapNotNull { it as? String }
    val incoming = when (field) {
        MetadataField.ARTISTS -> names?.let(::artistLines)
        MetadataField.DATE -> (value as? ReleaseDate)?.asTagValue()
        MetadataField.TRACK, MetadataField.DISC -> (value as? TrackIndex)?.asTagValue()
        else -> value as? String
    } ?: return false
    return incoming.trim() == existing.trim()
}
