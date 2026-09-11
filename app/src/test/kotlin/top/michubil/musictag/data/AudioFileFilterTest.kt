package top.michubil.musictag.data

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.MusicDocument
import java.util.concurrent.atomic.AtomicInteger

class AudioFileFilterTest {
    private fun file(id: Int) = MusicDocument("tree", "uri$id", "parent", "$id.flac", size = 100, modified = 1)

    @Test
    fun repeatedLargeScanReusesDurationsAcrossThresholdsAndPreservesOrder() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); 60_000 }
        val documents = (1..497).map(::file)
        val progress = mutableListOf<ScanProgress>()
        assertEquals(documents, filter.filter(documents, AudioFilters(20)) { progress += it })
        assertEquals(documents, filter.filter(documents, AudioFilters(60)))
        assertEquals(497, calls.get())
        assertEquals(ScanProgress(0, 497), progress.first())
        assertEquals(ScanProgress(497, 497), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.completed < b.completed })
        filter.clear()
        filter.filter(documents, AudioFilters(20))
        assertEquals(994, calls.get())
    }

    @Test
    fun changedStatsInvalidationAndLruEvictionCauseNewReads() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter(capacity = 2) { calls.incrementAndGet(); 60_000 }
        suspend fun read(document: MusicDocument) { filter.filter(listOf(document), AudioFilters(20)) }
        read(file(1)); read(file(2)); read(file(1)); read(file(3)); read(file(2))
        assertEquals(4, calls.get())
        read(file(2).copy(size = 101))
        read(file(2).copy(size = 101, modified = 2))
        filter.invalidate("uri2")
        read(file(2).copy(size = 101, modified = 2))
        assertEquals(7, calls.get())
    }

    @Test
    fun missingStatsAreNotCachedAndUnknownDurationsRemainVisible() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); null }
        val document = file(1).copy(modified = null)
        repeat(2) { assertEquals(listOf(document), filter.filter(listOf(document), AudioFilters(60))) }
        assertEquals(2, calls.get())
        assertEquals(listOf(file(2)), filter.filter(listOf(file(2)), AudioFilters(60)))
        assertEquals(listOf(file(2)), filter.filter(listOf(file(2)), AudioFilters(60)))
        assertEquals(3, calls.get())
    }

    @Test
    fun canceledScansStopWithoutPublishingOrCachingUnfinishedReads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); started.complete(Unit); gate.await(); 60_000 }
        val progress = mutableListOf<ScanProgress>()
        val scan = launch { filter.filter((1..50).map(::file), AudioFilters(20)) { progress += it } }
        started.await()
        scan.cancelAndJoin()
        val canceledCalls = calls.get()
        assertTrue(canceledCalls in 1..minOf(50, LocalFileWork.parallelism))
        assertEquals(listOf(ScanProgress(0, 50)), progress)
        gate.complete(Unit)
        filter.filter(listOf(file(1)), AudioFilters(20))
        assertEquals(canceledCalls + 1, calls.get())
    }

    @Test
    fun cacheClearDoesNotAcceptResultsFromAnEarlierScan() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); started.complete(Unit); gate.await(); 60_000 }
        val scan = launch { filter.filter(listOf(file(1)), AudioFilters(20)) }
        started.await()
        filter.clear()
        gate.complete(Unit)
        scan.join()
        filter.filter(listOf(file(1)), AudioFilters(20))
        assertEquals(2, calls.get())
    }

    @Test
    fun knownDurationsSkipProbe() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); 60_000 }
        val document = file(1)
        assertEquals(listOf(document), filter.filter(listOf(document), AudioFilters(20), knownDurations = mapOf(document to 30_000L)))
        assertEquals(0, calls.get())
        assertEquals(listOf(document), filter.filter(listOf(document), AudioFilters(20)))
        assertEquals(1, calls.get())
        assertEquals(listOf(document), filter.filter(listOf(document), AudioFilters(20)))
        assertEquals(1, calls.get())
    }

    @Test
    fun simultaneousScansCompleteAndSkipExcludedPaths() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); 30_000 }
        coroutineScope {
            launch { filter.filter((1..20).map(::file), AudioFilters(20)) }
            launch { filter.filter((21..40).map(::file), AudioFilters(20)) }
        }
        assertEquals(40, calls.get())
        val excluded = file(50).copy(relativePath = "Blocked/song.flac")
        assertTrue(filter.filter(listOf(excluded), AudioFilters(20, listOf("Blocked"))).isEmpty())
        assertEquals(40, calls.get())
    }
}
