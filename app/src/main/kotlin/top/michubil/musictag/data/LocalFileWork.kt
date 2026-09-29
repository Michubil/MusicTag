package top.michubil.musictag.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

data class ScanProgress(val completed: Int, val total: Int)

/** Shared runnable I/O limit; each map invocation separately bounds its in-flight file tasks. */
internal object LocalFileWork {
    const val parallelism = 6
    val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)

    // At most six workers per invocation, including suspended workers waiting for network or SAF.
    // The dispatcher limits runnable work, not the lifetime or HTTP fan-out of those workers.
    // Each worker owns its result slot, and joining publishes results in the original order.
    suspend fun <T, R> map(
        items: List<T>,
        onProgress: suspend (ScanProgress) -> Unit = {},
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val results = MutableList<Result<R>?>(items.size) { null }
        val next = AtomicInteger()
        val progressLock = Mutex()
        var completed = 0
        onProgress(ScanProgress(0, items.size))
        var reportedAt = TimeSource.Monotonic.markNow()
        List(minOf(parallelism, items.size)) {
            launch(dispatcher) {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    results[index] = Result.success(transform(items[index]))
                    ensureActive()
                    progressLock.withLock {
                        completed++
                        if (completed == items.size || reportedAt.elapsedNow() >= 100.milliseconds) {
                            onProgress(ScanProgress(completed, items.size))
                            reportedAt = TimeSource.Monotonic.markNow()
                        }
                    }
                }
            }
        }.joinAll()
        results.map { checkNotNull(it).getOrThrow() }
    }
}
