package top.michubil.musictag.data.network

import top.michubil.musictag.data.operationResult

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.FoundCandidate
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.match.PreparedScrape
import top.michubil.musictag.data.match.QueryPlan
import top.michubil.musictag.data.match.RecordingMatch
import top.michubil.musictag.data.match.ScrapeDisposition
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.match.MatchSelection
import top.michubil.musictag.data.match.UserQuery
import top.michubil.musictag.data.match.bestEvidence
import top.michubil.musictag.data.match.releaseFields
import top.michubil.musictag.data.match.retainReason
import top.michubil.musictag.data.match.sameRelease
import top.michubil.musictag.data.match.timedLyricsRejected
import top.michubil.musictag.data.model.*
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class MetadataSourcesClient(vararg clients: MusicSourceClient) {
    private val clients = clients.associateBy { it.source }

    fun forBatch(scope: CoroutineScope) = MetadataSourcesClient(*clients.values.map { it.forBatch(scope) }.toTypedArray())

    fun supportedFields(sources: ScrapeSources): Set<MetadataField> =
        sources.enabled().flatMap { clients.getValue(it).supportedFields }.toSet()

    suspend fun candidateCover(candidate: SongCandidate): CoverImage? =
        when (val cover = clients.getValue(candidate.source).metadata(candidate, setOf(MetadataField.COVER)).cover) {
            is RemoteValue.Available -> cover.value
            else -> null
        }

    fun blockedMessage(options: ScrapeOptions): String? {
        val selected = options.policies.filterValues { it.enabled }.keys
        if (selected.isEmpty()) return null
        val supported = supportedFields(options.sources)
        if (selected.any { it in supported }) return null
        return "网络源没有可用来源，请调整设置或取消这些字段"
    }

    suspend fun candidates(
        track: LocalTrack,
        options: ScrapeOptions,
        user: UserQuery? = null,
    ): CandidateSearch {
        if (options.policies.values.none { it.enabled }) {
            return CandidateSearch(emptyList(), MatchOutcome.None("没有选择字段"))
        }
        blockedMessage(options)?.let { reason ->
            return CandidateSearch(emptyList(), MatchOutcome.None(reason))
        }
        val enabled = options.sources.enabled()
        val collected = collect(track, user, enabled, emptyList())
        val decision = RecordingMatch.decide(track, collected.found, user)
        val outcome = if (decision.outcome is MatchOutcome.None && collected.failure != null) {
            MatchOutcome.None(collected.failure)
        } else decision.outcome
        return CandidateSearch(decision.ranked, outcome)
    }

    suspend fun relatedCandidates(track: LocalTrack, options: ScrapeOptions, selected: SongCandidate): CandidateSearch {
        val reference = referenceTrack(track, selected)
        val include = options.sources.enabled().filter { it != selected.source }
        val collected = collect(reference, null, include, listOf(selected))
        val decision = RecordingMatch.decide(reference, collected.found, null)
        return CandidateSearch(decision.ranked, decision.outcome)
    }

    suspend fun metadata(
        track: LocalTrack,
        options: ScrapeOptions,
        selection: MatchSelection,
    ): PreparedScrape {
        val selected = options.policies.filterValues { it.enabled }.keys
        if (selected.isEmpty()) return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.UNCHANGED))
        blockedMessage(options)?.let { return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.FAILED, it)) }
        val anchor = selection.accept.candidate
        val release = selection.accept.release
        val pool = selection.candidates
        val manual = selection.manual
        val evidence = bestEvidence(track, anchor, if (manual) null else UserQuery(anchor.title, anchor.artists))
        val enabled = options.sources.enabled()
        val matches = buildMap {
            put(anchor.source, anchor)
            for (source in enabled) {
                if (source == anchor.source) continue
                val compatible = pool.filter { it.source == source && RecordingMatch.supportsRecording(track, anchor, it) }
                val match = if (release != null) compatible.firstOrNull { sameRelease(release, it) }
                    ?: compatible.firstOrNull() else compatible.firstOrNull()
                if (match != null) put(source, match)
            }
        }
        val kept = mutableMapOf<MetadataField, String>()
        var failure: Throwable? = null
        var result = ScrapedMetadata()

        val rankedSources = pool.filter { matches[it.source]?.key == it.key }.map(SongCandidate::source).distinct()
        val sourceOrder = (listOf(anchor.source) + rankedSources).distinct().filter { it in enabled }
        val unsupported = selected.filterTo(mutableSetOf()) { field ->
            sourceOrder.none { source ->
                field in clients.getValue(source).supportedFields &&
                    (field != MetadataField.DISC || release == null || sameRelease(release, matches.getValue(source)))
            }
        }
        for (source in sourceOrder) {
            val candidate = matches.getValue(source)
            val allowIdentity = manual && source == anchor.source
            val fields = selected.filter { field ->
                if (result.value(field) is RemoteValue.Available || field in kept ||
                    field !in clients.getValue(source).supportedFields) return@filter false
                val reason = retainReason(field, evidence, release, allowIdentity)
                if (reason != null) {
                    kept.putIfAbsent(field, reason)
                    return@filter false
                }
                field !in releaseFields || (release != null && sameRelease(release, candidate))
            }.toSet()
            if (fields.isEmpty()) continue
            val response = operationResult { clients.getValue(source).metadata(candidate, fields) }
            failure = response.exceptionOrNull() ?: failure
            val data = response.getOrElse { ScrapedMetadata() }
            result = result.merge(data, fields)
            if (selected.all { result.value(it) is RemoteValue.Available || it in kept || it in unsupported }) break
        }
        val lyrics = result.lyrics
        if (lyrics is RemoteValue.Available && timedLyricsRejected(lyrics.value, evidence)) {
            result = result.copy(lyrics = RemoteValue.Unavailable)
            kept[MetadataField.LYRICS] = "时长不足以套用时间轴歌词"
        }
        for (field in selected - unsupported) {
            if (result.value(field) == RemoteValue.Unavailable) {
                kept.putIfAbsent(field, retainReason(field, evidence, release, manual) ?: failure?.message ?: "未取得${field.label}")
            }
        }
        if (selected.none { result.value(it) != RemoteValue.Unavailable }) {
            return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.FAILED,
                failure?.message ?: kept.values.firstOrNull() ?: "所选字段无法写入"))
        }
        return PreparedScrape(metadata = result, kept = kept, unsupported = unsupported)
    }

    private suspend fun collect(
        track: LocalTrack,
        user: UserQuery?,
        include: List<MusicSource>,
        preset: List<SongCandidate>,
    ): Collected {
        val buckets = include.associateWith { Bucket() }
        val queries = QueryPlan.plannedQueries(track, user)
        withTimeoutOrNull(CANDIDATE_BUDGET) {
            buckets.map { (source, bucket) ->
                async {
                    var cursor = 0
                    while (!bucket.gaveUp && bucket.searches < MAX_SEARCHES) {
                        val query = queries.getOrNull(cursor++)
                        val text = query ?: bucket.nextQuery ?: break
                        val page = if (query != null) 0 else bucket.nextPage ?: break
                        request(source, bucket, text, page)
                        enrich(track, user, source, bucket)
                        if (bucket.rows.values.any { RecordingMatch.canAccept(track, it, user) }) break
                    }
                }
            }.awaitAll()
        }
        val failure = buckets.values.firstOrNull { it.rows.isEmpty() && it.failed != null }?.failed
        val found = (listOf(preset) + buckets.values.map { it.rows.values.toList() }).flatMap { candidates ->
            candidates.mapIndexed { rank, candidate -> FoundCandidate(candidate, rank) }
        }.distinctBy { it.candidate.key }
        return Collected(found, failure)
    }

    private suspend fun request(source: MusicSource, bucket: Bucket, query: String, page: Int) {
        val client = clients.getValue(source)
        var attempt = operationResult { client.search(query, page) }
        if (attempt.isFailure && !bucket.retried && attempt.exceptionOrNull()?.isTransientSearchError() == true &&
            bucket.searches + 1 < MAX_SEARCHES) {
            bucket.searches++
            bucket.retried = true
            attempt = operationResult { client.search(query, page) }
        }
        bucket.searches++
        val pageResult = attempt.getOrElse { error ->
            bucket.failed = error.message ?: "来源暂不可用"
            bucket.consecutiveSearchFailures++
            bucket.nextPage = null
            return
        }
        bucket.consecutiveSearchFailures = 0
        pageResult.candidates.forEach { bucket.rows.putIfAbsent(it.key, it) }
        bucket.nextPage = pageResult.nextPage
        bucket.nextQuery = query
    }

    private suspend fun enrich(
        track: LocalTrack,
        user: UserQuery?,
        source: MusicSource,
        bucket: Bucket,
    ) {
        val pending = bucket.rows.values
            .filter { it.key !in bucket.detailed && RecordingMatch.needsDetail(track, it, user) }
            .take(MAX_DETAILS - bucket.detailed.size)
        for (candidate in pending) {
            bucket.detailed += candidate.key
            bucket.rows[candidate.key] = operationResult { clients.getValue(source).enrich(candidate) }.getOrElse { candidate }
        }
    }

    private fun referenceTrack(track: LocalTrack, candidate: SongCandidate): LocalTrack =
        LocalTrack(track.fileName, candidate.title, candidate.artists, candidate.album.ifBlank { track.album },
            track.durationMs?.takeIf { it > 0 } ?: candidate.durationMs)

    private fun Throwable.isTransientSearchError(): Boolean = this is IOException ||
        (this is MusicHttp.StatusException && code in setOf(408, 429, 500, 502, 503, 504))

    private class Bucket {
        val rows = linkedMapOf<String, SongCandidate>()
        val detailed = mutableSetOf<String>()
        var searches = 0
        var retried = false
        var consecutiveSearchFailures = 0
        var failed: String? = null
        var nextPage: Int? = null
        var nextQuery: String? = null
        val gaveUp: Boolean get() = consecutiveSearchFailures >= 2
    }

    private data class Collected(val found: List<FoundCandidate>, val failure: String?)

    private companion object {
        const val MAX_SEARCHES = 4
        const val MAX_DETAILS = 3
        val CANDIDATE_BUDGET = 20.seconds
    }
}
