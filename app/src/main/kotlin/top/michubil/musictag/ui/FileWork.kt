package top.michubil.musictag.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.michubil.musictag.data.LocalFileWork
import top.michubil.musictag.data.ScanProgress

internal data class FileWorkSummary(val success: Int, val failures: List<String>)

internal fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "操作失败"

internal fun <T> summarizeFileResults(files: List<T>, outcomes: List<Result<Unit>>, name: (T) -> String): FileWorkSummary {
    require(files.size == outcomes.size)
    return FileWorkSummary(outcomes.count { it.isSuccess }, outcomes.mapIndexedNotNull { index, result ->
        result.exceptionOrNull()?.let { error -> "${name(files[index])}：${error.userMessage()}" }
    })
}

/** Owns exclusive file-task lifetime. Cancellation still clears busy and runs the idle callback. */
internal class ExclusiveFileWork(
    private val scope: CoroutineScope,
    private val publishIdle: () -> Unit,
    private val afterIdle: () -> Unit,
) {
    fun launch(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } finally {
            publishIdle()
            afterIdle()
        }
    }
}

internal suspend fun <T> mapFileResults(
    files: List<T>,
    onProgress: suspend (ScanProgress) -> Unit = {},
    operation: suspend (T) -> Unit,
): List<Result<Unit>> = LocalFileWork.map(files, onProgress) { file ->
    try {
        operation(file)
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
