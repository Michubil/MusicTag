package top.michubil.musictag.data

import kotlinx.coroutines.*
import top.michubil.musictag.data.storage.MusicDocument

/** Small memory front cache; the repository also reuses durable Room durations. */
internal class AudioFileFilter(
    private val capacity: Int = 2048,
    private val probe: suspend (MusicDocument) -> Long?,
) {
    private data class Key(val tree: String, val uri: String, val name: String, val size: Long, val modified: Long)
    private data class Value(val duration: Long?)
    private val cache = LinkedHashMap<Key, Value>(16, 0.75f, true)
    private var generation = 0L

    @Synchronized fun clear() { generation++; cache.clear() }
    @Synchronized fun invalidate(uri: String) { generation++; cache.keys.removeAll { it.uri == uri } }

    private fun cacheKey(document: MusicDocument): Key? =
        if (document.hasReliableStats) Key(document.treeUri, document.uri, document.name, document.size!!, document.modified!!)
        else null

    private suspend fun duration(document: MusicDocument, known: Long?): Long? {
        currentCoroutineContext().ensureActive()
        if (known != null) return known
        val key = cacheKey(document)
        val (epoch, cached) = synchronized(this) { generation to key?.let(cache::get) }
        if (cached != null) return cached.duration
        val value = try { probe(document)?.takeIf { it > 0 } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (key != null) synchronized(this) {
            if (epoch == generation) {
                cache[key] = Value(value)
                while (cache.size > capacity) cache.remove(cache.keys.first())
            }
        }
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
