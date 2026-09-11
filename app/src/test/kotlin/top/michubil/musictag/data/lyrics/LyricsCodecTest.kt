package top.michubil.musictag.data.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricsCodecTest {
    @Test
    fun `parses offsets multiple timestamps and repeated lines`() {
        val parsed = LyricsCodec.parse(
            """
            [offset:100]
            [00:01.00][00:02.000]first
            [00:01.000]second
            credits
            """.trimIndent(),
        )

        assertEquals(LyricGroup(null, listOf("credits")), parsed[0])
        assertEquals(LyricGroup(1_100, listOf("first", "second")), parsed[1])
        assertEquals(LyricGroup(2_100, listOf("first")), parsed[2])
    }

    @Test
    fun `merges translation within tolerance after original lines`() {
        val merged = LyricsCodec.merge(
            "[00:01.000]Hello\n[00:02.000]Same",
            "[00:01.180]你好\n[00:02.000]Same",
        )

        assertEquals("[00:01.000]Hello\n[00:01.000]你好\n[00:02.000]Same", merged)
    }

    @Test
    fun `formats three digit timeline to two digits without changing existing two digits`() {
        val formatted = LyricsCodec.formatTimeline(
            "[00:01.234]First\n[12:34.56]Second\n[01:02:789]Third",
        )

        assertEquals(
            "[00:01.23]First\n[12:34.56]Second\n[01:02.78]Third",
            formatted,
        )
    }
}
