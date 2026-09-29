package top.michubil.musictag.data.match

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.operationResult
import top.michubil.musictag.data.network.FingerprintSuggestion

/** Fingerprint hints take priority; a platform candidate supplies the writable metadata. */
internal object AutomaticMatchResolver {
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
        return match
            ?: (textAccept?.candidate ?: text.ranked.firstOrNull())?.let {
                RecordingMatch.accept(track, text, it, textAccept?.basis ?: "自动选择的平台歌曲")
            }
                ?: text.copy(outcome = MatchOutcome.None(text.outcome.summary))
    }
}
