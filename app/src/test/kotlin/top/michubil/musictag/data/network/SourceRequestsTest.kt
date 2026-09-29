package top.michubil.musictag.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceRequestsTest {
    @Test
    fun cancellingOneConsumerKeepsTheSharedRequestAndSuccessfulResponse() = runBlocking {
        withTimeout(5_000) {
            val cache = RequestCache<String, String>(this, 2)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var loads = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("album") {
                    loads++
                    started.complete(Unit)
                    release.await()
                    "response"
                }
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("album") { error("Shared request must be reused") }
            }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals("response", second.await())
            assertEquals("response", cache.get("album") { error("Successful response must be reused") })
            assertEquals(1, loads)
        }
    }

    @Test
    fun lastConsumerCancelsTheRequestAndDoesNotPoisonTheNextLookup() = runBlocking {
        withTimeout(5_000) {
            val cache = RequestCache<String, String>(this, 2)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val consumer = async {
                cache.get("song") {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            consumer.cancelAndJoin()
            stopped.await()
            assertEquals("retry", cache.get("song") { "retry" })
        }
    }

    @Test
    fun failedResponsesAreRetriedAndBatchesDoNotShareEntries() = runBlocking {
        val cache = RequestCache<String, String>(this, 2)
        val failure = runCatching { cache.get("song") { throw IOException("offline") } }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals("recovered", cache.get("song") { "recovered" })
        val nextBatch = RequestCache<String, String>(this, 2)
        assertEquals("fresh", nextBatch.get("song") { "fresh" })
    }

    @Test
    fun cacheEvictsByWeightAndDoesNotRetainOversizedValues() = runBlocking {
        val cache = RequestCache<String, String>(this, 3, 5, String::length)
        cache.get("a") { "aaaa" }
        cache.get("b") { "bbb" }
        assertEquals("bbb", cache.get("b") { error("Recent value should remain") })
        assertEquals("new", cache.get("a") { "new" })
        assertEquals("oversized", cache.get("large") { "oversized" })
        assertEquals("retry", cache.get("large") { "retry" })
    }
}
