package top.michubil.musictag.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.SelectedCover
import top.michubil.musictag.data.TagEditorContent
import top.michubil.musictag.data.edit.TagDraft
import top.michubil.musictag.data.edit.TagEditSource
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.storage.MusicDocument

/** Owns only editor reads and drafts. It cannot change authorization, navigation or operation busy state. */
internal class TagEditorSession(
    private val scope: CoroutineScope,
    private val readTags: suspend (List<MusicDocument>, Boolean, AudioFilters) -> TagEditorContent,
    private val readCover: suspend (String) -> SelectedCover,
    private val state: () -> TagEditorState,
    private val publish: (TagEditorState) -> Unit,
    private val notify: (String) -> Unit,
) {
    private var readJob: Job? = null
    private var coverJob: Job? = null
    private var sources: List<TagEditSource> = emptyList()
    private var generation = 0L
    private var coverGeneration = 0L

    fun close() {
        generation++
        coverGeneration++
        readJob?.cancel()
        coverJob?.cancel()
        readJob = null
        coverJob = null
        sources = emptyList()
        publish(TagEditorState())
    }

    fun load(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters) {
        close()
        val request = generation
        publish(TagEditorState(loading = true, batch = selection.size != 1 || selection.single().isDirectory))
        readJob = scope.launch {
            try {
                val content = readTags(selection, recursive, filters)
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                sources = content.inputs.sources.toList()
                publish(state().copy(loading = false, draft = TagDraft.from(sources), count = sources.size,
                    failures = content.inputs.failures, fileName = sources.firstOrNull()?.document?.name,
                    originalArtwork = content.artwork))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == generation) publish(state().copy(loading = false, error = error.userMessage()))
            }
        }
    }

    fun changeDraft(transform: (TagDraft) -> TagDraft) {
        val current = state()
        if (!current.canChange) return
        val draft = transform(current.draft)
        publish(current.copy(draft = draft, validation = if (draft.changed.isEmpty()) null
            else runCatching { draft.mutation() }.exceptionOrNull()?.userMessage()))
    }

    fun removeCover() {
        if (!state().canChange) return
        changeDraft { it.copy(cover = null, changed = it.changed + MetadataField.COVER) }
        publish(state().copy(selectedArtwork = null))
    }

    fun loadCover(uri: String) {
        if (sources.isEmpty()) return
        coverJob?.cancel()
        val request = ++coverGeneration
        val editor = generation
        publish(state().copy(coverLoading = true))
        coverJob = scope.launch {
            try {
                val cover = readCover(uri)
                currentCoroutineContext().ensureActive()
                if (editor != generation || request != coverGeneration) return@launch
                publish(state().copy(coverLoading = false, selectedArtwork = cover.artwork))
                changeDraft { it.copy(cover = cover.image, changed = it.changed + MetadataField.COVER) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (editor == generation && request == coverGeneration) {
                    publish(state().copy(coverLoading = false))
                    notify(error.userMessage())
                }
            }
        }
    }

    fun prepareSave(version: Mp3TagVersion): TagSaveRequest? {
        val current = state()
        if (!current.canSave || sources.isEmpty()) return null
        val mutation = runCatching { current.draft.mutation(version) }.getOrElse {
            publish(current.copy(validation = it.userMessage()))
            return null
        }
        return TagSaveRequest(sources.toList(), mutation, current.failures.size)
    }
}
