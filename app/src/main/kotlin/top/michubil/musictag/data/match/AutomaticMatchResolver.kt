package top.michubil.musictag.data.match

import kotlinx.coroutines.CancellationException
import top.michubil.musictag.data.network.FingerprintSuggestion

internal data class AutomaticSearch(val search: CandidateSearch, val query: UserQuery?)

/** Fingerprint hints widen automatic recall; platform candidates still decide what can be written. */
internal object AutomaticMatchResolver {
    suspend fun resolve(
        search: suspend (UserQuery?) -> CandidateSearch,
        recognize: suspend () -> List<FingerprintSuggestion>,
    ): AutomaticSearch {
        val text = search(null)
        if (text.outcome is MatchOutcome.Accept) return AutomaticSearch(text, null)

        val suggestions = try {
            recognize()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return AutomaticSearch(text.copy(outcome = MatchOutcome.Review("自动指纹识别暂不可用，请手动匹配")), null)
        }.filter { it.title.isNotBlank() && it.artists.any(String::isNotBlank) }
            .distinctBy { comparableText(it.title) to it.artists.map(::identityKey).toSet() }
            .take(3)

        var accepted: AutomaticSearch? = null
        for (suggestion in suggestions) {
            val query = UserQuery(suggestion.title, suggestion.artists)
            val candidateSearch = search(query)
            val outcome = candidateSearch.outcome as? MatchOutcome.Accept ?: continue
            val previous = accepted?.search?.outcome as? MatchOutcome.Accept
            if (previous != null && previous.candidate.key != outcome.candidate.key &&
                !sameRecording(previous.candidate, outcome.candidate)) {
                return AutomaticSearch(text.copy(outcome = MatchOutcome.Review("指纹线索对应多首歌曲，请手动选择")), null)
            }
            if (accepted == null) accepted = AutomaticSearch(candidateSearch, query)
        }
        return accepted ?: AutomaticSearch(
            if (suggestions.isEmpty()) text else text.copy(
                outcome = MatchOutcome.Review("指纹找到线索，但已启用的来源未确认具体歌曲")),
            null,
        )
    }
}
