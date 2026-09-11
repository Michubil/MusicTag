package top.michubil.musictag.data

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LocalFileWorkTest {
    @Test
    fun largeBatchUsesProcessorSizedWorkersAndKeepsResultsAndProgressOrdered() = runBlocking {
        withTimeout(10_000) {
            val workers = minOf(490, LocalFileWork.parallelism)
            val started = AtomicInteger()
            val allStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val progress = mutableListOf<ScanProgress>()
            val work = async {
                LocalFileWork.map((0 until 490).toList(), onProgress = { progress += it }) { number ->
                    if (started.incrementAndGet() == workers) allStarted.complete(Unit)
                    gate.await()
                    number * 2
                }
            }
            allStarted.await()
            assertEquals(workers, started.get())
            gate.complete(Unit)
            assertEquals((0 until 490).map { it * 2 }, work.await())
            assertEquals((0..490).map { ScanProgress(it, 490) }, progress)
        }
    }

    @Test
    fun canceledBatchDoesNotStartQueuedItemsOrReportThemAsCompleted() = runBlocking {
        withTimeout(10_000) {
            val started = AtomicInteger()
            val first = CompletableDeferred<Unit>()
            val progress = mutableListOf<ScanProgress>()
            val total = LocalFileWork.parallelism * 2
            val work = launch {
                LocalFileWork.map<Int, Unit>((0 until total).toList(), onProgress = { progress += it }) {
                    started.incrementAndGet()
                    first.complete(Unit)
                    awaitCancellation()
                }
            }
            first.await()
            work.cancelAndJoin()
            assertTrue(started.get() in 1..LocalFileWork.parallelism)
            assertEquals(listOf(ScanProgress(0, total)), progress)
        }
    }

    @Test
    fun nullableResultsAndEmptyBatchesRemainValid() = runBlocking {
        assertEquals(listOf(null, "1", null), LocalFileWork.map(listOf(0, 1, 2)) { if (it == 1) "$it" else null })
        val progress = mutableListOf<ScanProgress>()
        assertEquals(emptyList<Int>(), LocalFileWork.map(emptyList<Int>(), onProgress = { progress += it }) { it })
        assertEquals(listOf(ScanProgress(0, 0)), progress)
    }
}
