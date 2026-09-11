package top.michubil.musictag.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReleaseDateTest {
    @Test
    fun parseAcceptsYearMonthAndDayPrecisionAndRejectsInvalidCalendars() {
        listOf("2026", "2026-09", "2024-02-29").forEach { value ->
            assertEquals(value, ReleaseDate.parse(value).asTagValue())
            assertEquals(ReleaseDate.parse(value), ReleaseDate.parseOrNull(value))
        }
        listOf("0000", "2026-13", "2026-02-29", "26", "2026-9-5", "2026-09-05T12:00").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ReleaseDate.parse(value) }
            assertNull(ReleaseDate.parseOrNull(value))
        }
    }
}
