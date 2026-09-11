package top.michubil.musictag.data.network

import top.michubil.musictag.data.match.SongMatcher
import top.michubil.musictag.data.match.sameRecording
import top.michubil.musictag.data.model.*

class MetadataSourcesClient(vararg clients: MusicSourceClient) {
    private val clients = clients.associateBy { it.source }

    private fun searchGroup(options: ScrapeOptions): MetadataGroup = MetadataGroup.entries.first { group ->
        group.fields.any { options.policies[it]?.enabled == true }
    }

    suspend fun candidates(track: LocalTrack, options: ScrapeOptions): List<MatchResult> {
        val result = mutableListOf<MatchResult>()
        var failure: Throwable? = null
        for (source in options.sources[searchGroup(options)].sources) {
            val found = sourceResult { clients.getValue(source).search(SongMatcher.query(track)) }
            failure = found.exceptionOrNull() ?: failure
            val songs = found.getOrNull().orEmpty()
            val ranked = SongMatcher.rank(track, songs)
            result += ranked
            if (SongMatcher.automaticFromRanked(ranked) != null) break
        }
        if (result.isEmpty() && failure != null) throw failure
        return result.distinctBy { it.candidate.key }
    }

    suspend fun metadata(track: LocalTrack, options: ScrapeOptions, forced: SongCandidate?): ScrapedMetadata {
        val selected = options.policies.filterValues { it.enabled }.keys
        val matches = mutableMapOf<MusicSource, SongCandidate?>()
        val downloaded = mutableMapOf<MusicSource, ScrapedMetadata>()
        val attempted = mutableMapOf<MusicSource, Set<MetadataField>>()
        var anchor = forced
        if (forced != null) matches[forced.source] = forced
        var failure: Throwable? = null

        fun order(group: MetadataGroup): List<MusicSource> =
            orderedSources(group, options.sources, forced, searchGroup(options))

        suspend fun fetch(source: MusicSource, required: Set<MetadataField>): ScrapedMetadata {
            val fields = metadataRequestFields(source, required, selected, attempted, downloaded) { order(it) }
            if (fields.isEmpty()) return downloaded[source] ?: ScrapedMetadata()
            val supported = fields.intersect(clients.getValue(source).supportedFields)
            val response = sourceResult {
                if (supported.isNotEmpty() && source !in matches) {
                    val referenceSong = anchor
                    val reference = referenceSong?.let { LocalTrack(track.fileName, it.title, it.artists,
                        it.album.takeIf(String::isNotBlank), it.durationMs) } ?: track
                    val candidates = clients.getValue(source).search(SongMatcher.query(reference))
                    val compatible = if (referenceSong == null) candidates else candidates.filter { sameRecording(referenceSong, it) }
                    matches[source] = SongMatcher.automatic(reference, compatible)?.candidate
                }
                val candidate = matches[source]
                if (candidate == null || supported.isEmpty()) ScrapedMetadata() else {
                    if (anchor == null) anchor = candidate
                    clients.getValue(source).metadata(candidate, supported)
                }
            }
            failure = response.exceptionOrNull() ?: failure
            attempted[source] = attempted[source].orEmpty() + fields
            return (downloaded[source] ?: ScrapedMetadata())
                .merge(response.getOrElse { ScrapedMetadata() }, fields, fallback = false)
                .also { downloaded[source] = it }
        }

        var result = ScrapedMetadata()
        for (group in MetadataGroup.entries) {
            val fields = group.fields.intersect(selected)
            if (fields.isEmpty()) continue
            var combined: ScrapedMetadata? = null
            for (source in order(group)) {
                val needed = fields.filter { combined?.value(it) !is RemoteValue.Available }.toSet()
                val data = fetch(source, needed)
                val merged = combined?.merge(data, fields, fallback = true) ?: data
                combined = merged
                if (fields.all { merged.value(it) is RemoteValue.Available }) break
            }
            result = result.merge(requireNotNull(combined), fields, fallback = false)
        }
        if (selected.none { result.value(it) != RemoteValue.Unavailable }) {
            throw IllegalStateException(failure?.message ?: "未找到足够可靠的匹配或所选内容，请单独选择该文件手动匹配", failure)
        }
        return result
    }
}

/** The explicitly chosen candidate controls the search group; other groups keep their settings. */
internal fun orderedSources(
    group: MetadataGroup,
    sources: ScrapeSources,
    forced: SongCandidate?,
    searchGroup: MetadataGroup,
): List<MusicSource> {
    val configured = sources[group].sources
    return if (forced != null && group == searchGroup)
        listOf(forced.source) + configured.filter { it != forced.source } else configured
}

/**
 * Fields this source should request in one batch: the caller's required set plus other groups
 * whose earlier sources already missed, excluding unselected and already-attempted fields.
 */
internal fun metadataRequestFields(
    source: MusicSource,
    required: Set<MetadataField>,
    selected: Set<MetadataField>,
    attempted: Map<MusicSource, Set<MetadataField>>,
    downloaded: Map<MusicSource, ScrapedMetadata>,
    order: (MetadataGroup) -> List<MusicSource>,
): Set<MetadataField> {
    val eligible = MetadataGroup.entries.flatMap { group ->
        val sequence = order(group)
        val index = sequence.indexOf(source)
        if (index < 0) emptyList() else group.fields.filter { field ->
            sequence.take(index).all { previous ->
                field in attempted[previous].orEmpty() && downloaded[previous]?.value(field) !is RemoteValue.Available
            }
        }
    }.toSet()
    return (required + eligible).intersect(selected) - attempted[source].orEmpty()
}
