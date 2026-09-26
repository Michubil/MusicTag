package top.michubil.musictag.data.match

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MusicSource
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
        assertEquals("Ａ—Song Artist", SongMatcher.query(untitled))
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
    fun automaticMatchingKeepsTheConfidenceAndAmbiguityGuards() {
        val track = local("Song A")
        val first = candidate(1, "Song A")
        val second = candidate(2, "Song B")
        val ranked = SongMatcher.rank(track, listOf(second, first))
        assertEquals(first, ranked.first().candidate)
        assertEquals(first, SongMatcher.automatic(track, listOf(second, first))?.candidate)
        assertNull(SongMatcher.automatic(track, listOf(first, candidate(3, "Song-A"))))
        assertNull(SongMatcher.automatic(local("kitten"), listOf(candidate(4, "sitting"))))
        assertNull(SongMatcher.automatic(track, emptyList()))
    }

    @Test
    fun filenameOrderIsConfirmedByTheCandidateRatherThanAssumed() {
        val song = candidate(1, "晴天").copy(artists = listOf("周杰伦"))
        for (name in listOf("周杰伦 - 晴天.flac", "晴天 - 周杰伦.MP3", "01. 周杰伦 - 晴天.wav",
            "01 - 晴天 — 周杰伦.flac")) {
            val track = LocalTrack(name, null, emptyList(), null, 100_000L)
            assertEquals(song, SongMatcher.automatic(track, listOf(song))?.candidate, name)
            assertEquals(song, SongMatcher.rank(track, listOf(candidate(2, "Other"), song)).first().candidate)
        }
    }

    @Test
    fun filenameSplitCannotHideAConflictingArtistEvenForALongTitle() {
        val title = "This is a sufficiently long song title"
        val track = LocalTrack("A - $title.mp3", null, emptyList(), null, 100_000L)
        val wrongSinger = candidate(1, title).copy(artists = listOf("B"))
        assertNull(SongMatcher.automatic(track, listOf(wrongSinger)))
        // Keep conflicting candidates visible for explicit manual selection.
        assertEquals(wrongSinger, SongMatcher.rank(track, listOf(wrongSinger)).single().candidate)
    }

    @Test
    fun ambiguousFilenameOrientationsRequireManualSelection() {
        val track = LocalTrack("Alpha - Bravo.flac", null, emptyList(), null, 100_000L)
        val first = candidate(1, "Bravo").copy(artists = listOf("Alpha"))
        val second = candidate(2, "Alpha").copy(artists = listOf("Bravo"))
        assertNull(SongMatcher.automatic(track, listOf(first, second)))
    }

    @Test
    fun taggedTitlesRemainAuthoritativeAndKnownArtistsMustConfirmFilenamePieces() {
        val tagged = local("01. Song.Live").copy(fileName = "Other - Wrong.mp3")
        assertEquals("01. Song.Live Artist", SongMatcher.query(tagged))
        assertEquals(candidate(1, "01. Song.Live"),
            SongMatcher.automatic(tagged, listOf(candidate(1, "01. Song.Live")))?.candidate)
        val untitled = local("Song").copy(title = null, fileName = "Artist - Song.mp3")
        assertEquals("Artist - Song", SongMatcher.query(untitled))
        assertEquals(candidate(1, "Song"), SongMatcher.automatic(untitled, listOf(candidate(1, "Song")))?.candidate)
        assertNull(SongMatcher.automatic(untitled.copy(artists = listOf("Another Artist")),
            listOf(candidate(1, "Song"))))
    }

    @Test
    fun punctuationWithinNamesAndUnstructuredNumbersAreNotSplit() {
        val track = LocalTrack("AC-DC.mp3", null, emptyList(), null, 100_000L)
        val remote = candidate(1, "DC").copy(artists = listOf("AC"))
        assertNull(SongMatcher.automatic(track, listOf(remote)))
        assertEquals("99 Luftballons", SongMatcher.query(track.copy(fileName = "99 Luftballons.flac")))
        assertEquals("A - B - C", SongMatcher.query(track.copy(fileName = "A - B - C.wav")))
    }

    @Test
    fun largeDurationConflictsCannotBeCompensatedByExactTags() {
        val remote = candidate(1, "Song")
        assertNull(SongMatcher.automatic(local("Song").copy(durationMs = 140_000L), listOf(remote)))
        assertNull(SongMatcher.automatic(local("Song").copy(durationMs = 110_001L), listOf(remote)))
        assertEquals(remote, SongMatcher.automatic(local("Song").copy(durationMs = 110_000L), listOf(remote))?.candidate)
        assertEquals(remote, SongMatcher.automatic(local("Song").copy(durationMs = null), listOf(remote))?.candidate)
    }

    @Test
    fun versionConflictsAreRejectedInBothDirectionsDespiteSimilarTitles() {
        val title = "This is a sufficiently long song title"
        for (suffix in listOf(" (Live)", ".Demo", " [Remix]", "（伴奏）", " (Acoustic)", " Live",
            "现场版", " (Remastered)")) {
            val studio = candidate(1, title)
            val version = candidate(2, title + suffix)
            assertNull(SongMatcher.automatic(local(title), listOf(version)), suffix)
            assertNull(SongMatcher.automatic(local(title + suffix), listOf(studio)), suffix)
            assertEquals(studio, SongMatcher.automatic(local(title), listOf(version, studio))?.candidate)
        }
    }

    @Test
    fun ordinaryWordsDoNotBecomeVersionAnnotations() {
        for ((title, version) in listOf("Live Forever With Me Tonight" to "Live", "Olive Trees In The Wind" to "Live",
            "Democracy Is Here To Stay" to "Demo", "Acoustic Dreams Of Tomorrow" to "Acoustic")) {
            val remote = candidate(1, title)
            assertEquals(remote, SongMatcher.automatic(local(title), listOf(remote))?.candidate)
            assertNull(SongMatcher.automatic(local(title), listOf(remote.copy(title = "$title ($version)"))))
        }
    }

    @Test
    fun allKnownArtistsMustBePresentForAutomaticSelection() {
        val track = local("Song").copy(artists = listOf("Artist", "Guest"))
        assertNull(SongMatcher.automatic(track, listOf(candidate(1, "Song"))))
        val duet = candidate(2, "Song").copy(artists = listOf("Guest", "Artist"))
        assertEquals(duet, SongMatcher.automatic(track, listOf(duet))?.candidate)
        assertNull(SongMatcher.automatic(local("Song"), listOf(duet.copy(artists = emptyList()))))
        assertNull(SongMatcher.automatic(local("Song").copy(artists = listOf("Artist One")),
            listOf(candidate(3, "Song").copy(artists = listOf("Artist Two")))))
    }

    @Test
    fun duplicateSearchRowsAndCrossSourceCorroborationDoNotCauseFalseAmbiguity() {
        val first = candidate(1, "Song")
        val otherSource = first.copy(source = MusicSource.QQ)
        assertEquals(first, SongMatcher.automatic(local("Song"), listOf(first, first, otherSource))?.candidate)
        assertEquals(otherSource, SongMatcher.automatic(local("Song"), listOf(otherSource, first))?.candidate)
    }

    @Test
    fun corroborationDoesNotHideCompetingRecordingsOrUnknownReleases() {
        val first = candidate(1, "Long Song Title")
        val otherSource = first.copy(source = MusicSource.QQ)
        val competing = candidate(2, "Long Song Titles")
        assertNull(SongMatcher.automatic(local(first.title), listOf(first, otherSource, competing)))
        assertNull(SongMatcher.automatic(local(first.title).copy(album = null),
            listOf(first.copy(album = ""), otherSource.copy(album = "Other Album"))))
        assertNull(SongMatcher.automatic(local(first.title).copy(album = null),
            listOf(first, otherSource.copy(album = "Other Album"))))
    }

    @Test
    fun blankTextAndNonPositiveDurationsDoNotCountAsMatchingEvidence() {
        val remote = candidate(1, "---").copy(artists = listOf(" "), album = "", durationMs = 0)
        assertNull(SongMatcher.automatic(LocalTrack(".mp3", " ", emptyList(), " ", 0), listOf(remote)))
        assertFalse(sameRecording(remote, remote))
        assertEquals(0.94, SongMatcher.rank(local("Song").copy(durationMs = 0),
            listOf(candidate(2, "Song"))).single().confidence, 1e-12)
    }
}
