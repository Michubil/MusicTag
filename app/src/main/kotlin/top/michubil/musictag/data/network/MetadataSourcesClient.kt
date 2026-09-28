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
import top.michubil.musictag.data.match.SourceReport
import top.michubil.musictag.data.match.SourceStatus
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

    fun blockedMessage(options: ScrapeOptions): String? {
        val names = MetadataGroup.entries.mapNotNull { group ->
            val selected = group.fields.filter { options.policies[it]?.enabled == true }
            if (selected.isEmpty()) return@mapNotNull null
            val usable = options.sources.enabled(group).any { source ->
                selected.any { field -> field in clients.getValue(source).supportedFields }
            }
            if (usable) null else group.label
        }
        if (names.isEmpty()) return null
        return names.joinToString("、") + "没有可用来源，请调整设置或取消这些字段"
    }

    suspend fun candidates(
        track: LocalTrack,
        options: ScrapeOptions,
        user: UserQuery? = null,
        prior: CandidateSearch? = null,
        retry: MusicSource? = null,
    ): CandidateSearch {
        val display = QueryPlan.display(track, user)
        if (options.policies.values.none { it.enabled }) {
            return CandidateSearch(emptyList(), emptyList(), MatchOutcome.None("没有选择字段"), display.first, display.second)
        }
        blockedMessage(options)?.let { reason ->
            return CandidateSearch(emptyList(), emptyList(), MatchOutcome.None(reason), display.first, display.second)
        }
        val order = searchOrder(options)
        val kept = if (retry != null && prior != null) prior.ranked.map { it.candidate }.filter { it.source != retry } else emptyList()
        val include = if (retry != null && prior != null) listOf(retry) else order
        val collected = collect(track, user, include, kept, order, stopWhenAccepted = retry == null)
        val reports = if (retry != null && prior != null) {
            prior.reports.filter { it.source != retry } + collected.reports
        } else collected.reports
        val decision = RecordingMatch.decide(track, collected.found, user)
        val outcome = when {
            decision.outcome is MatchOutcome.None && reports.any { it.status == SourceStatus.FAILED } ->
                MatchOutcome.None(reports.first { it.status == SourceStatus.FAILED }.message ?: "来源暂不可用")
            else -> decision.outcome
        }
        return CandidateSearch(decision.ranked, reports, outcome, display.first, display.second)
    }

    suspend fun metadata(
        track: LocalTrack,
        options: ScrapeOptions,
        forced: SongCandidate?,
        prior: CandidateSearch? = null,
        user: UserQuery? = null,
    ): PreparedScrape {
        val selected = options.policies.filterValues { it.enabled }.keys
        if (selected.isEmpty()) return PreparedScrape(disposition = ScrapeDisposition(ScrapeKind.UNCHANGED))
        blockedMessage(options)?.let { return PreparedScrape(disposition = ScrapeDisposition(ScrapeKind.FAILED, it)) }
        val reference = referenceTrack(track, forced)
        val search = if (forced != null && prior != null) prior else {
            val order = searchOrder(options)
            val include = if (forced == null) order else order.filter { it != forced.source }
            val collected = collect(reference, user, include, listOfNotNull(forced), order, stopWhenAccepted = true)
            val decision = RecordingMatch.decide(reference, collected.found, user)
            CandidateSearch(decision.ranked, collected.reports, decision.outcome, reference.title.orEmpty(), reference.artists.joinToString(" / "))
        }
        val outcome = if (forced != null) MatchOutcome.Accept(forced, forced, "已手动选择") else search.outcome
        val accept = outcome as? MatchOutcome.Accept ?: return PreparedScrape(disposition = when (outcome) {
            is MatchOutcome.Review -> ScrapeDisposition(ScrapeKind.REVIEW, outcome.summary)
            is MatchOutcome.None -> ScrapeDisposition(ScrapeKind.FAILED, outcome.summary)
            is MatchOutcome.Accept -> error("已接受的匹配不会进入失败结果")
        })
        return download(track, options, selected, accept, search, forced != null)
    }

    private suspend fun download(
        track: LocalTrack,
        options: ScrapeOptions,
        selected: Set<MetadataField>,
        accept: MatchOutcome.Accept,
        search: CandidateSearch,
        manual: Boolean,
    ): PreparedScrape {
        val anchor = accept.candidate
        val release = accept.release
        val pool = search.ranked.map { it.candidate }.let { rows ->
            if (rows.none { it.key == anchor.key }) listOf(anchor) + rows else rows
        }
        val evidence = bestEvidence(track, anchor, null)
        val matches = mutableMapOf<MusicSource, SongCandidate?>()
        matches[anchor.source] = anchor
        for (source in searchOrder(options)) {
            if (source in matches) continue
            val compatible = pool.filter { it.source == source && RecordingMatch.supportsRecording(track, anchor, it) }
            matches[source] = if (release != null) compatible.firstOrNull { sameRelease(release, it) } ?: compatible.firstOrNull()
                else compatible.firstOrNull()
        }
        val downloaded = mutableMapOf<MusicSource, ScrapedMetadata>()
        val attempted = mutableMapOf<MusicSource, Set<MetadataField>>()
        val kept = mutableMapOf<MetadataField, String>()
        var failure: Throwable? = null

        fun order(group: MetadataGroup) = orderedSources(group, options.sources, if (manual) anchor else null, searchGroup(options))

        suspend fun fetch(source: MusicSource, required: Set<MetadataField>): ScrapedMetadata {
            val candidate = matches[source]
            val allowIdentity = manual && source == anchor.source
            val fields = metadataRequestFields(source, required, selected, attempted, downloaded, ::order).filter { field ->
                val reason = retainReason(field, evidence, release, allowIdentity)
                if (reason != null) {
                    kept.putIfAbsent(field, reason)
                    return@filter false
                }
                if (candidate == null) return@filter false
                field !in releaseFields || (release != null && sameRelease(release, candidate))
            }.toSet()
            if (fields.isEmpty()) return downloaded[source] ?: ScrapedMetadata()
            val supported = fields.intersect(clients.getValue(source).supportedFields)
            val response = sourceResult {
                if (candidate == null || supported.isEmpty()) ScrapedMetadata()
                else clients.getValue(source).metadata(candidate, supported)
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
            if (order(group).isEmpty()) {
                fields.forEach { kept.putIfAbsent(it, "没有可用来源") }
                continue
            }
            var combined: ScrapedMetadata? = null
            for (source in order(group)) {
                val needed = fields.filter { combined?.value(it) !is RemoteValue.Available && it !in kept }.toSet()
                if (needed.isEmpty()) break
                val data = fetch(source, needed)
                val merged = combined?.merge(data, fields, fallback = true) ?: data
                combined = merged
                if (fields.all { merged.value(it) is RemoteValue.Available || it in kept }) break
            }
            result = result.merge(requireNotNull(combined), fields, fallback = false)
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
        val obtained = selected.filter { result.value(it) != RemoteValue.Unavailable }
        val disposition = when {
            obtained.isEmpty() && failure != null -> ScrapeDisposition(ScrapeKind.FAILED, failure?.message)
            obtained.isEmpty() -> ScrapeDisposition(ScrapeKind.REVIEW, kept.values.firstOrNull() ?: "所选字段无法写入")
            obtained.size == selected.size -> ScrapeDisposition(ScrapeKind.COMPLETE)
            else -> ScrapeDisposition(ScrapeKind.PARTIAL, kept.values.firstOrNull())
        }
        return PreparedScrape(result, disposition, kept)
    }

    private suspend fun collect(
        track: LocalTrack,
        user: UserQuery?,
        include: List<MusicSource>,
        preset: List<SongCandidate>,
        order: List<MusicSource>,
        stopWhenAccepted: Boolean,
    ): Collected {
        val started = TimeSource.Monotonic.markNow()
        fun expired() = started.elapsedNow() >= CANDIDATE_BUDGET
        val buckets = include.distinct().associateWith { Bucket() }
        val queries = QueryPlan.plannedQueries(track, user)
        var cursor = 0
        while (true) {
            if (expired()) {
                buckets.values.forEach { it.incomplete = true }
                break
            }
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
                                if (bucket.gaveUp || bucket.searches >= MAX_SEARCHES) {
                                    if (bucket.searches >= MAX_SEARCHES) bucket.incomplete = true
                                    return@async
                                }
                                request(source, bucket, query, 0, expired())
                            }
                        }.awaitAll()
                    }
                    cursor++
                }
                enrich(track, user, buckets, expired())
                true
            }
            if (completed == null) {
                buckets.values.forEach { it.incomplete = true }
                break
            }
            val found = found(preset, buckets, order)
            val searched = buckets.values.any { it.searches > 0 || it.failed != null }
            if (stopWhenAccepted && searched && RecordingMatch.decide(track, found, user).outcome is MatchOutcome.Accept) break
            if (query == null && buckets.values.none { it.nextPage != null }) break
        }
        return Collected(found(preset, buckets, order), buckets.map { (source, bucket) -> bucket.report(source) })
    }

    private suspend fun request(source: MusicSource, bucket: Bucket, query: String, page: Int, expired: Boolean) {
        if (expired || bucket.searches >= MAX_SEARCHES) {
            bucket.incomplete = true
            return
        }
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
            bucket.nextPage = null
            return
        }
        if (pageResult.issues.isNotEmpty()) bucket.issues = true
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
        for ((source, bucket) in buckets) {
            val pending = bucket.rows.map { it.first }
                .filter { it.key !in bucket.detailed && RecordingMatch.needsDetail(track, it, user) }
                .take((MAX_DETAILS - bucket.details).coerceAtLeast(0))
            for (candidate in pending) {
                if (expired || bucket.details >= MAX_DETAILS) {
                    bucket.incomplete = true
                    break
                }
                bucket.detailed += candidate.key
                val updated = sourceResult { clients.getValue(source).enrich(candidate) }.getOrElse { error ->
                    bucket.detailError = error.message ?: "候选详情暂不可用"
                    bucket.incomplete = true
                    candidate
                }
                bucket.details++
                val index = bucket.rows.indexOfFirst { it.first.key == candidate.key }
                if (index >= 0) bucket.rows[index] = updated to bucket.rows[index].second
            }
        }
    }

    private fun found(preset: List<SongCandidate>, buckets: Map<MusicSource, Bucket>, order: List<MusicSource>): List<FoundCandidate> {
        val items = mutableListOf<FoundCandidate>()
        val seen = mutableSetOf<String>()
        fun indexOf(source: MusicSource) = order.indexOf(source).let { if (it < 0) order.size else it }
        for (candidate in preset) {
            if (seen.add(candidate.key)) items += FoundCandidate(candidate, indexOf(candidate.source), items.size)
        }
        for ((source, bucket) in buckets) {
            for ((candidate, rank) in bucket.rows) {
                if (seen.add(candidate.key)) items += FoundCandidate(candidate, indexOf(source), rank)
            }
        }
        return items
    }

    private fun searchGroup(options: ScrapeOptions): MetadataGroup = MetadataGroup.entries.first { group ->
        group.fields.any { options.policies[it]?.enabled == true }
    }

    private fun searchOrder(options: ScrapeOptions): List<MusicSource> = MetadataGroup.entries.flatMap { group ->
        if (group.fields.none { options.policies[it]?.enabled == true }) emptyList() else options.sources.enabled(group)
    }.distinct()

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
        var failed: String? = null
        var incomplete = false
        var issues = false
        var detailError: String? = null
        var nextPage: Int? = null
        var nextQuery: String? = null
        val gaveUp: Boolean get() = failed != null

        fun add(candidates: List<SongCandidate>) {
            for (candidate in candidates) {
                if (seen.add(candidate.key)) rows += candidate to rows.size
            }
        }

        fun report(source: MusicSource): SourceReport {
            val status = when {
                failed != null && rows.isEmpty() -> SourceStatus.FAILED
                incomplete || failed != null -> SourceStatus.INCOMPLETE
                rows.isEmpty() -> SourceStatus.EMPTY
                else -> SourceStatus.READY
            }
            val message = failed ?: detailError ?: "部分歌曲信息无法解析".takeIf { issues }
            return SourceReport(source, status, rows.size, message)
        }
    }

    private data class Collected(val found: List<FoundCandidate>, val reports: List<SourceReport>)

    private companion object {
        const val MAX_SEARCHES = 4
        const val MAX_DETAILS = 3
        val CANDIDATE_BUDGET = 20.seconds
    }
}

/** The explicitly chosen candidate leads its field group when that source is still enabled. */
internal fun orderedSources(
    group: MetadataGroup,
    sources: ScrapeSources,
    forced: SongCandidate?,
    searchGroup: MetadataGroup,
): List<MusicSource> {
    val configured = sources.enabled(group)
    val forcedSource = forced?.source
    return if (forcedSource != null && group == searchGroup && forcedSource in configured) {
        listOf(forcedSource) + configured.filter { it != forcedSource }
    } else configured
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
