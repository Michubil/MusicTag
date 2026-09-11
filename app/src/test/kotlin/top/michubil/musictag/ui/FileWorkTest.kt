package top.michubil.musictag.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.ScanProgress

class FileWorkTest {
    @Test
    fun cancellingABatchReleasesBusyAndStillRefreshes() = runBlocking {
        withTimeout(5_000) {
            val events = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val work = ExclusiveFileWork(this, publishIdle = { events += "idle" }, afterIdle = { events += "refresh" })
            val job = work.launch {
                events += "start"
                started.complete(Unit)
                awaitCancellation()
            }
            started.await()
            job.cancelAndJoin()
            assertEquals(listOf("start", "idle", "refresh"), events)
        }
    }

    @Test
    fun oneFailedFileDoesNotAbortTheBatchAndCountsAsFinishedProgress() = runBlocking {
        withTimeout(5_000) {
            val progress = mutableListOf<ScanProgress>()
            val results = mapFileResults(listOf("ok", "bad", "later"), onProgress = { progress += it }) { name ->
                if (name == "bad") error("no")
            }
            assertTrue(results[0].isSuccess)
            assertTrue(results[1].isFailure)
            assertTrue(results[2].isSuccess)
            assertEquals((0..3).map { ScanProgress(it, 3) }, progress)
        }
    }
}
