package top.michubil.musictag.data

import kotlinx.coroutines.*
import top.michubil.musictag.data.storage.MusicDocument

internal class AudioFileFilter(
    private val probe: suspend (MusicDocument) -> Long?,
) {
    private suspend fun duration(document: MusicDocument, known: Long?): Long? {
        currentCoroutineContext().ensureActive()
        if (known != null) return known
        val value = operationResult { probe(document)?.takeIf { it > 0 } }.getOrNull()
        currentCoroutineContext().ensureActive()
        return value
    }

    suspend fun filter(
        documents: List<MusicDocument>, filters: AudioFilters,
        knownDurations: Map<MusicDocument, Long> = emptyMap(),
        onDuration: (MusicDocument, Long) -> Unit = { _, _ -> },
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): List<MusicDocument> {
        val candidates = documents.filterNot(filters::excludes)
        if (filters.minimumSeconds == 0) return candidates
        val audioIndices = candidates.indices.filter { !candidates[it].isDirectory }
        val allowed = BooleanArray(candidates.size) { candidates[it].isDirectory }
        LocalFileWork.map(audioIndices, onProgress) { index ->
            val document = candidates[index]
            val duration = duration(document, knownDurations[document])
            if (duration != null) onDuration(document, duration)
            allowed[index] = filters.allowsDuration(duration)
        }
        return candidates.filterIndexed { index, _ -> allowed[index] }
    }
}
