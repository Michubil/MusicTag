package top.michubil.musictag.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.FoundCandidate
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.match.PreparedScrape
import top.michubil.musictag.data.match.QueryPlan
import top.michubil.musictag.data.match.RecordingMatch
import top.michubil.musictag.data.match.ScrapeDisposition
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.match.UserQuery
import top.michubil.musictag.data.match.bestEvidence
import top.michubil.musictag.data.match.releaseFields
import top.michubil.musictag.data.match.retainReason
import top.michubil.musictag.data.match.sameRelease
import top.michubil.musictag.data.match.timedLyricsRejected
import top.michubil.musictag.data.model.*
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class MetadataSourcesClient(vararg clients: MusicSourceClient) {
    private val clients = clients.associateBy { it.source }

    suspend fun candidateCover(candidate: SongCandidate): CoverImage? =
        when (val cover = clients.getValue(candidate.source).metadata(candidate, setOf(MetadataField.COVER)).cover) {
            is RemoteValue.Available -> cover.value
            else -> null
        }

    fun blockedMessage(options: ScrapeOptions): String? {
        val selected = options.policies.filterValues { it.enabled }.keys
        if (selected.isEmpty()) return null
        val enabled = options.sources.enabled()
        if (enabled.any { source -> selected.any { it in clients.getValue(source).supportedFields } }) return null
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

    suspend fun metadata(
        track: LocalTrack,
        options: ScrapeOptions,
        forced: SongCandidate?,
        prior: CandidateSearch? = null,
        user: UserQuery? = null,
    ): PreparedScrape {
        val selected = options.policies.filterValues { it.enabled }.keys
        if (selected.isEmpty()) return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.UNCHANGED))
        blockedMessage(options)?.let { return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.FAILED, it)) }
        val search = prior ?: run {
            if (forced == null) candidates(track, options, user) else {
                val reference = referenceTrack(track, forced)
                val include = options.sources.enabled().filter { it != forced.source }
                val collected = collect(reference, user, include, listOf(forced))
                val decision = RecordingMatch.decide(reference, collected.found, user)
                CandidateSearch(decision.ranked, decision.outcome)
            }
        }
        val outcome = if (forced != null) MatchOutcome.Accept(forced, forced, "已手动选择") else search.outcome
        val accept = when (outcome) {
            is MatchOutcome.Accept -> outcome
            is MatchOutcome.Review -> return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.REVIEW, outcome.summary))
            is MatchOutcome.None -> return PreparedScrape(stop = ScrapeDisposition(ScrapeKind.FAILED, outcome.summary))
        }
        return download(track, options, selected, accept, search, forced != null, user)
    }

    private suspend fun download(
        track: LocalTrack,
        options: ScrapeOptions,
        selected: Set<MetadataField>,
        accept: MatchOutcome.Accept,
        search: CandidateSearch,
        manual: Boolean,
        user: UserQuery?,
    ): PreparedScrape {
        val anchor = accept.candidate
        val release = accept.release
        val pool = search.ranked
        val evidence = bestEvidence(track, anchor, if (manual) null else user)
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
            val response = sourceResult { clients.getValue(source).metadata(candidate, fields) }
            failure = response.exceptionOrNull() ?: failure
            val data = response.getOrElse { ScrapedMetadata() }
            result = result.merge(data, fields)
            if (selected.all { result.value(it) is RemoteValue.Available || it in kept }) break
        }
        val lyrics = result.lyrics
        if (lyrics is RemoteValue.Available && timedLyricsRejected(lyrics.value, evidence)) {
            result = result.copy(lyrics = RemoteValue.Unavailable)
            kept[MetadataField.LYRICS] = "时长不足以套用时间轴歌词"
        }
        for (field in selected) {
            if (result.value(field) == RemoteValue.Unavailable) {
                kept.putIfAbsent(field, retainReason(field, evidence, release, manual) ?: failure?.message ?: "未取得${field.label}")
            }
        }
        if (selected.none { result.value(it) != RemoteValue.Unavailable }) {
            val stop = if (failure != null) ScrapeDisposition(ScrapeKind.FAILED, failure.message)
                else ScrapeDisposition(ScrapeKind.REVIEW, kept.values.firstOrNull() ?: "所选字段无法写入")
            return PreparedScrape(stop = stop)
        }
        return PreparedScrape(metadata = result, kept = kept)
    }

    private suspend fun collect(
        track: LocalTrack,
        user: UserQuery?,
        include: List<MusicSource>,
        preset: List<SongCandidate>,
    ): Collected {
        val started = TimeSource.Monotonic.markNow()
        fun expired() = started.elapsedNow() >= CANDIDATE_BUDGET
        val buckets = include.distinct().associateWith { Bucket() }
        val queries = QueryPlan.plannedQueries(track, user)
        var cursor = 0
        while (true) {
            if (expired()) break
            val query = queries.getOrNull(cursor)
            val paging = if (query == null) buckets.filterValues {
                it.nextPage != null && it.nextQuery != null && it.searches < MAX_SEARCHES
            } else emptyMap()
            if (query == null && paging.isEmpty()) break
            val remainingMs = (CANDIDATE_BUDGET - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(1)
            val completed = withTimeoutOrNull(remainingMs) {
                if (query == null) {
                    coroutineScope {
                        paging.map { (source, bucket) ->
                            async { request(source, bucket, requireNotNull(bucket.nextQuery), requireNotNull(bucket.nextPage), expired()) }
                        }.awaitAll()
                    }
                } else {
                    coroutineScope {
                        buckets.map { (source, bucket) ->
                            async {
                                if (bucket.gaveUp || bucket.searches >= MAX_SEARCHES) return@async
                                request(source, bucket, query, 0, expired())
                            }
                        }.awaitAll()
                    }
                    cursor++
                }
                enrich(track, user, buckets, expired())
                true
            }
            if (completed == null) break
            if (buckets.values.all { bucket ->
                    bucket.gaveUp || bucket.searches >= MAX_SEARCHES ||
                        bucket.rows.any { (candidate, _) -> RecordingMatch.canAccept(track, candidate, user) }
                }) break
            if (query == null && buckets.values.none { it.nextPage != null }) break
        }
        val failure = buckets.values.firstOrNull { it.rows.isEmpty() && it.failed != null }?.failed
        return Collected(found(preset, buckets), failure)
    }

    private suspend fun request(source: MusicSource, bucket: Bucket, query: String, page: Int, expired: Boolean) {
        if (expired || bucket.searches >= MAX_SEARCHES) return
        val client = clients.getValue(source)
        var attempt = sourceResult { client.search(query, page) }
        if (attempt.isFailure && !bucket.retried && attempt.exceptionOrNull()?.isTransientSearchError() == true &&
            bucket.searches + 1 < MAX_SEARCHES) {
            bucket.searches++
            bucket.retried = true
            attempt = sourceResult { client.search(query, page) }
        }
        bucket.searches++
        val pageResult = attempt.getOrElse { error ->
            bucket.failed = error.message ?: "来源暂不可用"
            bucket.consecutiveSearchFailures++
            bucket.nextPage = null
            return
        }
        bucket.consecutiveSearchFailures = 0
        bucket.add(pageResult.candidates)
        bucket.nextPage = pageResult.nextPage
        bucket.nextQuery = query
    }

    private suspend fun enrich(
        track: LocalTrack,
        user: UserQuery?,
        buckets: Map<MusicSource, Bucket>,
        expired: Boolean,
    ) {
        coroutineScope {
            buckets.map { (source, bucket) ->
                async {
                    val pending = bucket.rows.map { it.first }
                        .filter { it.key !in bucket.detailed && RecordingMatch.needsDetail(track, it, user) }
                        .take((MAX_DETAILS - bucket.details).coerceAtLeast(0))
                    for (candidate in pending) {
                        if (expired || bucket.details >= MAX_DETAILS) break
                        bucket.detailed += candidate.key
                        val updated = sourceResult { clients.getValue(source).enrich(candidate) }.getOrElse { candidate }
                        bucket.details++
                        val index = bucket.rows.indexOfFirst { it.first.key == candidate.key }
                        if (index >= 0) bucket.rows[index] = updated to bucket.rows[index].second
                    }
                }
            }.awaitAll()
        }
    }

    private fun found(preset: List<SongCandidate>, buckets: Map<MusicSource, Bucket>): List<FoundCandidate> {
        val items = mutableListOf<FoundCandidate>()
        val seen = mutableSetOf<String>()
        for (candidate in preset) {
            if (seen.add(candidate.key)) items += FoundCandidate(candidate, items.size)
        }
        for ((_, bucket) in buckets) {
            for ((candidate, rank) in bucket.rows) {
                if (seen.add(candidate.key)) items += FoundCandidate(candidate, rank)
            }
        }
        return items
    }

    private fun referenceTrack(track: LocalTrack, candidate: SongCandidate?): LocalTrack = candidate?.let { song ->
        LocalTrack(track.fileName, song.title, song.artists, song.album.takeIf(String::isNotBlank) ?: track.album,
            track.durationMs?.takeIf { it > 0 } ?: song.durationMs)
    } ?: track

    private fun Throwable.isTransientSearchError(): Boolean = this is IOException ||
        (this is MusicHttp.StatusException && code in setOf(408, 429, 500, 502, 503, 504))

    private class Bucket {
        val rows = mutableListOf<Pair<SongCandidate, Int>>()
        val detailed = mutableSetOf<String>()
        private val seen = mutableSetOf<String>()
        var searches = 0
        var details = 0
        var retried = false
        var consecutiveSearchFailures = 0
        var failed: String? = null
        var nextPage: Int? = null
        var nextQuery: String? = null
        val gaveUp: Boolean get() = consecutiveSearchFailures >= 2

        fun add(candidates: List<SongCandidate>) {
            for (candidate in candidates) {
                if (seen.add(candidate.key)) rows += candidate to rows.size
            }
        }
    }

    private data class Collected(val found: List<FoundCandidate>, val failure: String?)

    private companion object {
        const val MAX_SEARCHES = 4
        const val MAX_DETAILS = 3
        val CANDIDATE_BUDGET = 20.seconds
    }
}
