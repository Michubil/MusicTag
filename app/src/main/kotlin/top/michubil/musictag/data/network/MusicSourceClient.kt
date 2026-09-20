package top.michubil.musictag.data.network

import kotlinx.coroutines.CancellationException
import top.michubil.musictag.data.model.*

interface MusicSourceClient {
    val source: MusicSource
    val supportedFields: Set<MetadataField> get() = MetadataField.entries.toSet()
    suspend fun search(query: String): List<SongCandidate>
    suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata
}

internal suspend fun <T> sourceResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
