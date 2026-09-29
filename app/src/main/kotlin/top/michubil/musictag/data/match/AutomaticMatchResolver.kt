package top.michubil.musictag.data.match

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.network.FingerprintSuggestion

internal data class AutomaticSearch(val search: CandidateSearch, val query: UserQuery?)

/** Fingerprint hints widen automatic recall; platform candidates still decide what can be written. */
internal object AutomaticMatchResolver {
    suspend fun resolve(
        track: LocalTrack,
        search: suspend (UserQuery?) -> CandidateSearch,
        recognize: suspend () -> List<FingerprintSuggestion>,
    ): AutomaticSearch {
        val (text, recognized) = coroutineScope {
            val text = async { search(null) }
            val recognized = async {
                try {
                    recognize()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            text.await() to recognized.await()
        }
        val textAccept = text.outcome as? MatchOutcome.Accept
        val suggestions = recognized ?: return AutomaticSearch(
            if (textAccept != null) text else text.copy(
                outcome = MatchOutcome.Review("自动指纹识别暂不可用，请手动匹配")),
            null,
        )
        val hints = suggestions.filter { it.title.isNotBlank() && it.artists.any(String::isNotBlank) }
            .distinctBy { comparableText(it.title) to it.artists.map(::identityKey).toSet() }
            .take(3)
        if (hints.isEmpty()) return AutomaticSearch(text, null)

        val matches = coroutineScope {
            hints.map { suggestion ->
                async {
                    val query = UserQuery(suggestion.title, suggestion.artists)
                    if (textAccept != null && RecordingMatch.canAccept(track, textAccept.candidate, query)) {
                        AutomaticSearch(text, null)
                    } else {
                        val candidateSearch = search(query)
                        if (candidateSearch.outcome is MatchOutcome.Accept) AutomaticSearch(candidateSearch, query) else null
                    }
                }
            }.awaitAll()
        }
        var accepted: AutomaticSearch? = null
        for (match in matches.filterNotNull()) {
            val outcome = match.search.outcome as MatchOutcome.Accept
            val previous = accepted?.search?.outcome as? MatchOutcome.Accept
            if (previous != null && previous.candidate.key != outcome.candidate.key &&
                !sameRecording(previous.candidate, outcome.candidate)) {
                return AutomaticSearch(text.copy(outcome = MatchOutcome.Review("指纹线索对应多首歌曲，请手动选择")), null)
            }
            if (accepted == null) accepted = match
        }
        return accepted ?: AutomaticSearch(
            text.copy(outcome = MatchOutcome.Review(
                if (textAccept != null) "音频指纹与文字匹配不一致，请手动确认" else
                    "指纹找到线索，但已启用的来源未确认具体歌曲")),
            null,
        )
    }
}
