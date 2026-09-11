package top.michubil.musictag.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileWorkSummaryTest {
    @Test
    fun failuresKeepTheirFileNamesAndInputOrder() {
        val outcomes = listOf(Result.success(Unit), Result.failure(IllegalStateException("读取失败")),
            Result.failure(IllegalStateException(" ")))
        val summary = summarizeFileResults(listOf("ok.mp3", "bad.flac", "unknown.wav"), outcomes) { it }
        assertEquals(1, summary.success)
        assertEquals(listOf("bad.flac：读取失败", "unknown.wav：操作失败"), summary.failures)
    }

    @Test
    fun emptyBatchHasNoFailures() {
        assertEquals(FileWorkSummary(0, emptyList()), summarizeFileResults(emptyList<String>(), emptyList()) { it })
    }
}
