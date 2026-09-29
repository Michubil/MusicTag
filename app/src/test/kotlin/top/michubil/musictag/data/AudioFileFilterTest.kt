package top.michubil.musictag.data

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.MusicDocument
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AudioFileFilterTest {
    private fun file(id: Int) = MusicDocument("tree", "uri$id", "parent", "$id.flac", size = 100, modified = 1)

    @Test
    fun knownDurationsSkipProbeAndPreserveOrder() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); 60_000 }
        val documents = (1..497).map(::file)
        val progress = mutableListOf<ScanProgress>()
        val known = documents.associateWith { 60_000L }
        assertEquals(documents, filter.filter(documents, AudioFilters(20), knownDurations = known) { progress += it })
        assertEquals(0, calls.get())
        assertEquals(ScanProgress(0, 497), progress.first())
        assertEquals(ScanProgress(497, 497), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.completed < b.completed })
        filter.filter(documents, AudioFilters(20))
        assertEquals(497, calls.get())
    }

    @Test
    fun missingDurationsAreProbedAndReportedForRepositoryStorage() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); 60_000 }
        val known = file(1)
        val missing = file(2)
        val durations = ConcurrentHashMap<MusicDocument, Long>()
        assertEquals(listOf(known, missing), filter.filter(
            listOf(known, missing), AudioFilters(20), knownDurations = mapOf(known to 30_000L),
            onDuration = { document, duration -> durations[document] = duration },
        ))
        assertEquals(mapOf(known to 30_000L, missing to 60_000L), durations)
        assertEquals(1, calls.get())
    }

    @Test
    fun unknownDurationsRemainVisibleAndAreRetried() = runBlocking {
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); null }
        val document = file(1)
        repeat(2) { assertEquals(listOf(document), filter.filter(listOf(document), AudioFilters(60))) }
        assertEquals(2, calls.get())
    }

    @Test
    fun canceledScansStopWithoutPublishingUnfinishedReads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val filter = AudioFileFilter { calls.incrementAndGet(); started.complete(Unit); gate.await(); 60_000 }
        val progress = mutableListOf<ScanProgress>()
        val scan = launch { filter.filter((1..50).map(::file), AudioFilters(20)) { progress += it } }
        started.await()
        scan.cancelAndJoin()
        assertTrue(calls.get() in 1..minOf(50, LocalFileWork.parallelism))
        assertEquals(listOf(ScanProgress(0, 50)), progress)
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
