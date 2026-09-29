package top.michubil.musictag.data.network

import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import top.michubil.musictag.data.model.*

data class SearchPage(
    val candidates: List<SongCandidate>,
    val nextPage: Int? = null,
)

interface MusicSourceClient {
    val source: MusicSource
    /** Batch-local response reuse; implementations keep their existing source rate limits. */
    fun forBatch(scope: CoroutineScope): MusicSourceClient = this
    val supportedFields: Set<MetadataField> get() = MetadataField.entries.toSet()
    suspend fun search(query: String, page: Int = 0): SearchPage
    suspend fun enrich(candidate: SongCandidate): SongCandidate = candidate
    suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata
}

internal fun JSONArray.songCandidates(source: MusicSource, parse: (JSONObject) -> SongCandidate?): List<SongCandidate> {
    val candidates = objects().mapNotNull(parse).distinctBy(SongCandidate::key)
    check(length() == 0 || candidates.isNotEmpty()) { "${source.label}搜索结果无法解析" }
    return candidates
}

