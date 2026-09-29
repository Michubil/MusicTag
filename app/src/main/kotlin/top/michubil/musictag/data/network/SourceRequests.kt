package top.michubil.musictag.data.network

import top.michubil.musictag.data.operationResult

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import top.michubil.musictag.data.model.CoverImage

/** One source's responses for one scrape batch; never stores a file's matching decision. */
internal class SourceRequests(scope: CoroutineScope) {
    val searches = RequestCache<Pair<String, Int>, SearchPage>(scope, 64)
    val songs = RequestCache<Long, JSONObject>(scope, 64, 4 * 1024 * 1024) { it.toString().length * 2 }
    val albums = RequestCache<Long, JSONObject>(scope, 16, 4 * 1024 * 1024) { it.toString().length * 2 }
    val covers = RequestCache<String, CoverImage>(scope, 16, 16 * 1024 * 1024) { it.bytes.size }
}

/** Shares an in-flight request; only the last departing consumer cancels it. */
internal class RequestCache<K, V>(
    private val scope: CoroutineScope,
    private val capacity: Int,
    private val maxWeight: Int = Int.MAX_VALUE,
    private val weight: (V) -> Int = { 1 },
) {
    private class Pending<V>(val result: Deferred<Result<V>>, var consumers: Int = 0)
    private val completed = LinkedHashMap<K, Pair<V, Int>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Pending<V>>()
    private var completedWeight = 0L

    suspend fun get(key: K, load: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        val request = synchronized(this) {
            completed[key]?.let { return it.first }
            pending.getOrPut(key) {
                Pending(scope.async(start = CoroutineStart.LAZY) { operationResult { load() } })
            }.also { it.consumers++ }
        }
        try {
            val result = request.result.await()
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (pending[key] === request) {
                    pending.remove(key)
                    result.getOrNull()?.let { value ->
                        val size = weight(value)
                        if (size <= maxWeight) {
                            completed[key] = value to size
                            completedWeight += size
                            val oldest = completed.entries.iterator()
                            while (completed.size > capacity || completedWeight > maxWeight) {
                                completedWeight -= oldest.next().value.second
                                oldest.remove()
                            }
                        }
                    }
                }
            }
            return result.getOrThrow()
        } finally {
            synchronized(this) {
                request.consumers--
                if (request.consumers == 0 && pending[key] === request) {
                    pending.remove(key)
                    request.result.cancel()
                }
            }
        }
    }
}

internal suspend fun <K, V> RequestCache<K, V>?.load(key: K, fetch: suspend () -> V): V =
    if (this == null) fetch() else get(key, fetch)
