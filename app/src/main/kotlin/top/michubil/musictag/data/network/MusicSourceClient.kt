package top.michubil.musictag.data.network

import kotlinx.coroutines.CancellationException
import top.michubil.musictag.data.model.*

data class SearchPage(
    val candidates: List<SongCandidate>,
    val nextPage: Int? = null,
    val issues: List<String> = emptyList(),
)

interface MusicSourceClient {
    val source: MusicSource
    val supportedFields: Set<MetadataField> get() = MetadataField.entries.toSet()
    suspend fun search(query: String, page: Int = 0): SearchPage
    suspend fun enrich(candidate: SongCandidate): SongCandidate = candidate
    suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata
}

internal suspend fun <T> sourceResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
