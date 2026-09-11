package top.michubil.musictag.data.match

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.SongCandidate

class SongMatcherTest {
    private fun local(title: String) = LocalTrack("song.mp3", title, listOf("Artist"), "Album", 100_000L)
    private fun candidate(id: Long, title: String) = SongCandidate(id, title, listOf("Artist"), "Album",
        null, 100_000L, null, null, null)

    @Test
    fun missingDurationUsesTheNeutralWeightInsteadOfAMatch() {
        val remote = candidate(1, "Song")
        assertEquals(1.0, SongMatcher.rank(local("Song"), listOf(remote)).single().confidence, 1e-12)
        assertEquals(0.94, SongMatcher.rank(local("Song").copy(durationMs = null), listOf(remote)).single().confidence, 1e-12)
    }

    @Test
    fun filenameExtensionsAreFoldedButTitleVersionSuffixesAreKept() {
        val untitled = LocalTrack("Ａ—Song.MP3", null, listOf("Artist"), "Album", 100_000L)
        val song = candidate(1, "a song")
        assertEquals(1.0, SongMatcher.rank(untitled, listOf(song)).single().confidence, 1e-12)
        assertEquals(song, SongMatcher.automatic(untitled, listOf(song))?.candidate)
        assertEquals(comparableFileName("Ａ—Song.MP3"), comparableText("a song"))
        assertEquals(comparableText("Ａ—Song"), comparableText("a song"))
        assertNotEquals(comparableText("Song.Live"), comparableText("Song.Demo"))
        assertNotEquals(comparableText("Song.Live"), comparableText("Song"))
    }

    @Test
    fun versionSuffixesDoNotCountAsTheSameRecording() {
        val live = candidate(1, "Song.Live")
        val demo = candidate(2, "Song.Demo")
        val studio = candidate(3, "Song")
        val foldedLive = candidate(4, "Ｓong.Live")
        assertFalse(sameRecording(live, demo))
        assertFalse(sameRecording(live, studio))
        assertTrue(sameRecording(live, foldedLive))
    }

    @Test
    fun editDistancesKeepTheirScoresAcrossDifferentBufferLengths() {
        for ((left, right, distance) in listOf(Triple("kitten", "sitting", 3),
            Triple("a", "abcdefgh", 7), Triple("Saturday", "Sunday", 3), Triple("abc", "a", 2))) {
            val expected = (1.0 - distance.toDouble() / maxOf(left.length, right.length)) * 0.52 + 0.24 + 0.12 + 0.12
            assertEquals(expected, SongMatcher.rank(local(left), listOf(candidate(1, right))).single().confidence, 1e-12)
        }
    }

    @Test
    fun reusingRankedCandidatesKeepsTheConfidenceAndAmbiguityGuards() {
        val track = local("Song A")
        val first = candidate(1, "Song A")
        val second = candidate(2, "Song B")
        val ranked = SongMatcher.rank(track, listOf(second, first))
        assertEquals(first, ranked.first().candidate)
        assertEquals(SongMatcher.automatic(track, listOf(second, first)), SongMatcher.automaticFromRanked(ranked))
        assertEquals(first, SongMatcher.automaticFromRanked(ranked)?.candidate)
        assertNull(SongMatcher.automatic(track, listOf(first, candidate(3, "Song-A"))))
        assertNull(SongMatcher.automatic(local("kitten"), listOf(candidate(4, "sitting"))))
        assertNull(SongMatcher.automatic(track, emptyList()))
    }
}
