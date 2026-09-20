package top.michubil.musictag.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class SerialGateTest {
    @Test
    fun concurrentRunsStaySerial() = runBlocking {
        withTimeout(5_000) {
            val gate = SerialGate()
            val overlapping = AtomicInteger()
            val peak = AtomicInteger()
            val workers = List(LocalFileWork.parallelism.coerceAtLeast(2) * 2) {
                async(LocalFileWork.dispatcher) {
                    gate.run {
                        val live = overlapping.incrementAndGet()
                        peak.accumulateAndGet(live, ::maxOf)
                        Thread.sleep(5)
                        overlapping.decrementAndGet()
                    }
                }
            }
            workers.forEach { it.await() }
            assertEquals(1, peak.get())
        }
    }

    @Test
    fun waitersDoNotStarveOtherLocalIo() = runBlocking {
        withTimeout(5_000) {
            val workers = LocalFileWork.parallelism
            if (workers < 2) return@withTimeout
            val gate = SerialGate()
            val holding = CompletableDeferred<Unit>()
            val release = CompletableFuture<Unit>()
            val holder = launch(LocalFileWork.dispatcher) {
                gate.run {
                    holding.complete(Unit)
                    release.get()
                }
            }
            holding.await()
            repeat(workers) {
                launch(LocalFileWork.dispatcher) { gate.run { } }
            }
            yield()
            val other = async(LocalFileWork.dispatcher) { "ok" }
            assertEquals("ok", other.await())
            release.complete(Unit)
            holder.join()
            assertTrue(holder.isCompleted)
        }
    }
}
