package top.michubil.musictag.data.match

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.operationResult
import top.michubil.musictag.data.network.FingerprintSuggestion

/** Fingerprint hints take priority; a platform candidate supplies the writable metadata. */
internal object MatchResolver {
    fun select(track: LocalTrack, search: CandidateSearch, forced: SongCandidate? = null): MatchSelection? {
        val accept = if (forced != null) MatchOutcome.Accept(forced, forced, "已手动选择") else
            (search.outcome as? MatchOutcome.Accept) ?: search.ranked.firstOrNull()?.let { candidate ->
                RecordingMatch.accept(track, search, candidate, "自动选择的平台歌曲").outcome as MatchOutcome.Accept
            }
        return accept?.let { MatchSelection(it, search.ranked, manual = forced != null) }
    }

    suspend fun resolve(
        track: LocalTrack,
        search: suspend (UserQuery?) -> CandidateSearch,
        recognize: suspend () -> List<FingerprintSuggestion>,
    ): CandidateSearch {
        val (text, recognized) = coroutineScope {
            val text = async { search(null) }
            val recognized = async { operationResult { recognize() }.getOrDefault(emptyList()) }
            text.await() to recognized.await()
        }
        val hints = recognized.filter { it.title.isNotBlank() && it.artists.any(String::isNotBlank) }
            .distinctBy { comparableText(it.title) to it.artists.map(::identityKey).toSet() }
            .take(3)
        val textAccept = text.outcome as? MatchOutcome.Accept
        val match = coroutineScope {
            val searches = hints.map { suggestion ->
                async {
                    val query = UserQuery(suggestion.title, suggestion.artists)
                    val existing = textAccept?.candidate?.takeIf { RecordingMatch.canAccept(track, it, query) }
                        ?: text.ranked.firstOrNull { RecordingMatch.canAccept(track, it, query) }
                    if (existing != null) {
                        RecordingMatch.accept(track, text, existing,
                            textAccept?.takeIf { it.candidate == existing }?.basis ?: "音频指纹对应的平台歌曲")
                    } else {
                        val candidateSearch = search(query)
                        val candidate = candidateSearch.ranked.firstOrNull {
                            RecordingMatch.canAccept(track, it, query)
                        }
                        candidate?.let { RecordingMatch.accept(track, candidateSearch, it,
                            "音频指纹对应的平台歌曲") }
                    }
                }
            }
            try {
                // Keep fingerprint priority even when a later search finishes first.
                for (search in searches) {
                    search.await()?.let { return@coroutineScope it }
                }
                null
            } finally {
                searches.forEach { it.cancel() }
            }
        }
        return match ?: select(track, text)?.let {
            RecordingMatch.accept(track, text, it.accept.candidate, it.accept.basis)
        }
            ?: text.copy(outcome = MatchOutcome.None(text.outcome.summary))
    }
}
