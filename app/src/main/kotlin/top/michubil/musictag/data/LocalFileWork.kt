package top.michubil.musictag.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Shared local I/O budget, sized to the processors Android makes available to this process. */
internal object LocalFileWork {
    val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)

    // A fixed number of workers keeps large batches from creating a coroutine/copy per file.
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
                        onProgress(ScanProgress(completed, items.size))
                    }
                }
            }
        }.joinAll()
        results.map { checkNotNull(it).getOrThrow() }
    }
}
