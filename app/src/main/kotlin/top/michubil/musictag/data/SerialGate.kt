package top.michubil.musictag.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes blocking work so waiters suspend instead of occupying LocalFileWork threads. */
internal class SerialGate {
    private val lock = Mutex()

    suspend fun <T> run(block: () -> T): T = lock.withLock { block() }
}
