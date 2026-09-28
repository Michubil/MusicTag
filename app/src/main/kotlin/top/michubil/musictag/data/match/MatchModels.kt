package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument

sealed interface MatchOutcome {
    val summary: String

    data class Accept(val candidate: SongCandidate, val release: SongCandidate?, val basis: String) : MatchOutcome {
        override val summary: String get() = basis
    }
    data class Review(override val summary: String) : MatchOutcome
    data class None(override val summary: String) : MatchOutcome
}

data class UserQuery(val title: String, val artists: List<String>)

data class CandidateSearch(
    val ranked: List<SongCandidate>,
    val outcome: MatchOutcome,
)

enum class ScrapeKind { COMPLETE, PARTIAL, UNCHANGED, REVIEW, FAILED }

data class ScrapeDisposition(val kind: ScrapeKind, val reason: String? = null)

data class PreparedScrape(
    val metadata: ScrapedMetadata = ScrapedMetadata(),
    val stop: ScrapeDisposition? = null,
    val kept: Map<MetadataField, String> = emptyMap(),
)

data class MatchSession(
    val uri: String,
    val size: Long?,
    val modified: Long?,
    val sources: ScrapeSources,
    val policies: Map<MetadataField, FieldPolicy>,
    val search: CandidateSearch,
) {
    fun sameFile(document: MusicDocument): Boolean =
        uri == document.uri && size == document.size && modified == document.modified
}
