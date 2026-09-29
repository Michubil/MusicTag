package top.michubil.musictag.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.ScanProgress
import top.michubil.musictag.data.rename.FilenameTemplate
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.RenameInputs
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.rename.planRenames
import top.michubil.musictag.data.storage.MusicDocument

data class RenameState(
    val preset: RenamePreset = RenamePreset.TITLE_ARTIST,
    val customPattern: String = "@1-@2",
    val loading: Boolean = false,
    val progress: ScanProgress? = null,
    val entries: List<RenameEntry> = emptyList(),
    val error: String? = null,
) {
    val pattern: String get() = if (preset == RenamePreset.CUSTOM) customPattern else preset.pattern
    val canApply: Boolean get() = !loading && error == null && entries.any { it.willRename }
    val loadingMessage: String get() = progress?.let {
        if (it.completed == it.total) "正在检查文件名" else "正在读取标签(${it.completed}/${it.total})"
    } ?: "正在准备标签读取"
}

/** Read inputs and their preview share one lifetime; commits capture entries before closing it. */
internal class RenameSession(
    private val scope: CoroutineScope,
    private val read: suspend (List<MusicDocument>, Boolean, AudioFilters, suspend (ScanProgress) -> Unit) -> RenameInputs,
    private val state: () -> RenameState,
    private val publish: (RenameState) -> Unit,
) {
    private var job: Job? = null
    private var inputs: RenameInputs? = null
    private var generation = 0L

    fun close() {
        generation++
        job?.cancel()
        job = null
        inputs = null
        publish(state().copy(loading = false, progress = null, entries = emptyList(), error = null))
    }

    fun load(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters) {
        close()
        val request = generation
        publish(state().copy(loading = true))
        job = scope.launch {
            try {
                val sessionContext = currentCoroutineContext()
                val loaded = read(selection, recursive, filters) { progress ->
                    withContext(sessionContext) {
                        ensureActive()
                        if (request == generation) publish(state().copy(progress = progress))
                    }
                }
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                inputs = loaded
                publish(state().copy(loading = false))
                updatePreview()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) publish(state().copy(loading = false, error = error.userMessage()))
            }
        }
    }

    fun setPreset(preset: RenamePreset) {
        publish(state().copy(preset = preset))
        updatePreview()
    }

    fun setPattern(pattern: String) {
        publish(state().copy(customPattern = pattern.take(240)))
        updatePreview()
    }

    private fun updatePreview() {
        val snapshot = state()
        val template = runCatching { FilenameTemplate(snapshot.pattern) }.getOrElse {
            publish(snapshot.copy(entries = emptyList(), error = it.userMessage()))
            return
        }
        val loaded = inputs ?: return
        publish(snapshot.copy(entries = planRenames(loaded, template), error = null))
    }
}
