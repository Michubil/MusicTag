package top.michubil.musictag.data

import kotlinx.coroutines.CancellationException

/** Recoverable operation failures are values; cancellation still stops the caller. */
internal inline fun <T> operationResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
