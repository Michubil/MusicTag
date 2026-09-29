package top.michubil.musictag.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.match.MatchSession
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument

data class CandidateState(
    val loading: Boolean = false,
    val session: MatchSession? = null,
    val error: String? = null,
) {
    val items: List<SongCandidate> get() = session?.search?.ranked.orEmpty()
    val notice: String? get() = session?.search?.let { search ->
        search.outcome.summary.takeUnless { search.ranked.isNotEmpty() && search.outcome is MatchOutcome.Accept }
    }
}

/** Owns candidate reads only. Closing it cannot release an active file write. */
internal class CandidateSession(
    private val scope: CoroutineScope,
    private val expand: suspend (List<MusicDocument>, Boolean, AudioFilters) -> List<MusicDocument>,
    private val search: suspend (MusicDocument, ScrapeOptions) -> CandidateSearch,
    private val publish: (CandidateState) -> Unit,
    private val open: suspend () -> Unit,
    private val notify: (String) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L

    fun close() {
        generation++
        job?.cancel()
        job = null
        publish(CandidateState())
    }

    fun load(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters, options: ScrapeOptions) {
        close()
        val request = generation
        publish(CandidateState(loading = true))
        job = scope.launch {
            try {
                val files = expand(selection, recursive, filters)
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                if (files.size != 1) {
                    publish(CandidateState())
                    notify("手动匹配时请选择一个 FLAC、MP3 或 WAV 文件")
                    return@launch
                }
                open()
                val file = files.single()
                val result = search(file, options)
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                publish(CandidateState(session = MatchSession(file.uri, file.size, file.modified,
                    options.sources, options.policies, result)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request != generation) return@launch
                publish(CandidateState(error = error.userMessage()))
                notify(error.userMessage())
            }
        }
    }
}
