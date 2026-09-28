package top.michubil.musictag.data.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SongCandidate

class RecordingMatchTest {
    private fun local(title: String) = LocalTrack("song.mp3", title, listOf("Artist"), "Album", 100_000L)
    private fun candidate(id: Long, title: String) = SongCandidate(id, title, listOf("Artist"), "Album",
        null, 100_000L, null, null, null)
    private fun found(candidates: List<SongCandidate>) = candidates.mapIndexed { index, candidate ->
        FoundCandidate(candidate, sourceIndex = if (candidate.source == MusicSource.QQ) 1 else 0, platformRank = index)
    }
    private fun accept(track: LocalTrack, candidates: List<SongCandidate>) =
        (RecordingMatch.decide(track, found(candidates), null).outcome as? MatchOutcome.Accept)?.candidate

    @Test
    fun trailingSourceNotesAreRemovedWithoutDroppingVersionMarks() {
        assertEquals("好戲開場", titleCore("好戲開場 (《活俠傳》遊戲配樂)"))
        assertEquals("Song (Live)", titleCore("Song (Live)"))
        assertEquals("Song (Acoustic)", titleCore("Song (Acoustic)"))
        assertEquals("Song", titleCore("Song (《活俠傳》遊戲配樂)"))
    }

    @Test
    fun missingDurationDoesNotBecomeSupportingEvidenceOrBlockAcceptance() {
        val remote = candidate(1, "Song")
        val missing = RecordingMatch.decide(local("Song").copy(durationMs = null), found(listOf(remote)), null)
        assertEquals(remote, (missing.outcome as MatchOutcome.Accept).candidate)
        assertTrue(missing.ranked.single().explanations.contains("时长未知"))
        assertTrue(missing.ranked.single().explanations.contains("标题一致"))
    }

    @Test
    fun filenameExtensionsAreFoldedButTitleVersionSuffixesAreKept() {
        val untitled = LocalTrack("Ａ—Song.MP3", null, listOf("Artist"), "Album", 100_000L)
        val song = candidate(1, "a song")
        assertEquals(song, accept(untitled, listOf(song)))
        assertEquals("Ａ—Song", QueryPlan.display(untitled, null).first)
        assertEquals(comparableText("Ａ—Song"), comparableText("a song"))
        assertEquals(comparableText("Song.Live"), comparableText("Song.Live"))
        assertTrue(versionMarks("Song.Live") != versionMarks("Song.Demo"))
        assertTrue(versionMarks("Song.Live") != versionMarks("Song"))
    }

    @Test
    fun queriesKeepTheFullTitleThenBodyAndDoNotStripVersionText() {
        val track = LocalTrack("other.flac", "月華の円舞曲 -Valse di Fantastica-",
            listOf("宮野幸子", "森下唯"), null, null)
        val queries = QueryPlan.plannedQueries(track, null)
        assertEquals("月華の円舞曲 -Valse di Fantastica- 宮野幸子 森下唯", queries.first())
        assertTrue(queries.contains("月華の円舞曲"))
        assertTrue(queries.contains("Valse di Fantastica"))
        assertEquals(listOf("Song Artist", "Song"), QueryPlan.plannedQueries(local("Song"), null).take(2))
        assertEquals(listOf("Song"), QueryPlan.plannedQueries(local("Song").copy(artists = emptyList()), null).take(1))
        assertTrue(QueryPlan.plannedQueries(local("Song (Live)"), null).none { it == "Song" })
        assertTrue(QueryPlan.plannedQueries(local("Song - Acoustic"), null).contains("Song - Acoustic"))
        assertTrue(QueryPlan.plannedQueries(local("Song -Live-"), null).any { it.contains("-Live-") })
        assertTrue(QueryPlan.plannedQueries(local("Song -Live Version-"), null).any { it.contains("Live Version") })
        assertEquals("月華の円舞曲", ScriptForms.simplified("月華の円舞曲"))
    }

    @Test
    fun aPlainFilenameCanStillBeSearchedWhenTheTagTitleIsStale() {
        val track = LocalTrack("Correct title.mp3", "Old title", listOf("Artist"), null, null)
        val queries = QueryPlan.plannedQueries(track, null)
        assertTrue("Correct title" in queries)
        assertEquals("Old title Artist", queries.first())
    }

    @Test
    fun japaneseCreditMismatchIsShownAndNotAutoAccepted() {
        val track = LocalTrack("other.flac", "月華の円舞曲 -Valse di Fantastica-",
            listOf("宮野幸子/森下唯"), null, null)
        val remote = candidate(1, "月華の円舞曲").copy(artists = listOf("下村陽子"))
        val decision = RecordingMatch.decide(track, found(listOf(remote)), null)
        assertTrue(decision.outcome is MatchOutcome.Review)
        assertEquals("署名不同", (decision.outcome as MatchOutcome.Review).summary)
        assertEquals(remote, decision.ranked.single().candidate)
        val confirmed = remote.copy(artists = listOf("宮野幸子", "森下唯"))
        assertEquals(confirmed, accept(track, listOf(confirmed)))
        assertNull(splitArtistValue("AC/DC"))
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
    fun weakTitleSimilarityDoesNotBlockAReliableRecording() {
        val track = local("Song A")
        val first = candidate(1, "Song A")
        val second = candidate(2, "Song B")
        val ranked = RecordingMatch.decide(track, found(listOf(second, first)), null).ranked
        assertEquals(first, ranked.first().candidate)
        assertEquals(first, accept(track, listOf(second, first)))
        assertEquals(first, accept(track, listOf(first, candidate(3, "Song-A"))))
        assertTrue(RecordingMatch.decide(local("kitten"), found(listOf(candidate(4, "sitting"))), null).outcome is MatchOutcome.Review)
        assertTrue(RecordingMatch.decide(track, emptyList(), null).outcome is MatchOutcome.None)
        val near = candidate(2, "Long Song Titles")
        val exact = candidate(1, "Long Song Title")
        assertEquals(exact, accept(local(exact.title), listOf(exact, exact.copy(source = MusicSource.QQ), near)))
    }

    @Test
    fun filenameOrderIsConfirmedByTheCandidateRatherThanAssumed() {
        val song = candidate(1, "晴天").copy(artists = listOf("周杰伦"))
        for (name in listOf("周杰伦 - 晴天.flac", "晴天 - 周杰伦.MP3", "01. 周杰伦 - 晴天.wav", "01 - 晴天 — 周杰伦.flac")) {
            val track = LocalTrack(name, null, emptyList(), null, 100_000L)
            assertEquals(song, accept(track, listOf(song)), name)
            val ranked = RecordingMatch.decide(track, found(listOf(candidate(2, "Other"), song)), null).ranked
            assertEquals(song, ranked.first().candidate)
        }
    }

    @Test
    fun filenameSplitCannotHideAConflictingArtist() {
        val title = "This is a sufficiently long song title"
        val track = LocalTrack("A - $title.mp3", null, emptyList(), null, 100_000L)
        val wrongSinger = candidate(1, title).copy(artists = listOf("B"))
        val decision = RecordingMatch.decide(track, found(listOf(wrongSinger)), null)
        assertTrue(decision.outcome is MatchOutcome.Review)
        assertEquals(wrongSinger, decision.ranked.single().candidate)
    }

    @Test
    fun ambiguousFilenameOrientationsRequireManualSelection() {
        val track = LocalTrack("Alpha - Bravo.flac", null, emptyList(), null, 100_000L)
        val first = candidate(1, "Bravo").copy(artists = listOf("Alpha"))
        val second = candidate(2, "Alpha").copy(artists = listOf("Bravo"))
        assertTrue(RecordingMatch.decide(track, found(listOf(first, second)), null).outcome is MatchOutcome.Review)
    }

    @Test
    fun contradictoryTagAndFilenameNeedReview() {
        val tagged = local("01. Song.Live").copy(fileName = "Other - Wrong.mp3")
        assertEquals("01. Song.Live", QueryPlan.display(tagged, null).first)
        assertTrue(RecordingMatch.decide(tagged, found(listOf(candidate(1, "01. Song.Live"))), null).outcome is MatchOutcome.Review)
        val untitled = local("Song").copy(title = null, fileName = "Artist - Song.mp3")
        assertEquals("Artist - Song", QueryPlan.display(untitled, null).first)
        assertEquals(candidate(1, "Song"), accept(untitled, listOf(candidate(1, "Song"))))
        assertNull(accept(untitled.copy(artists = listOf("Another Artist")), listOf(candidate(1, "Song"))))
    }

    @Test
    fun punctuationWithinNamesAndUnstructuredNumbersAreNotSplit() {
        val track = LocalTrack("AC-DC.mp3", null, emptyList(), null, 100_000L)
        val remote = candidate(1, "DC").copy(artists = listOf("AC"))
        assertNull(accept(track, listOf(remote)))
        assertEquals("99 Luftballons", QueryPlan.display(track.copy(fileName = "99 Luftballons.flac", title = null), null).first)
        assertEquals("A - B - C", QueryPlan.display(track.copy(fileName = "A - B - C.wav", title = null), null).first)
    }

    @Test
    fun largeDurationConflictsCannotBeCompensatedByExactTags() {
        val remote = candidate(1, "Song")
        assertNull(accept(local("Song").copy(durationMs = 140_000L), listOf(remote)))
        assertNull(accept(local("Song").copy(durationMs = 110_001L), listOf(remote)))
        assertEquals(remote, accept(local("Song").copy(durationMs = 110_000L), listOf(remote)))
        assertEquals(remote, accept(local("Song").copy(durationMs = null), listOf(remote)))
    }

    @Test
    fun versionConflictsAreRejectedInBothDirectionsDespiteSimilarTitles() {
        val title = "This is a sufficiently long song title"
        for (suffix in listOf(" (Live)", ".Demo", " [Remix]", "（伴奏）", " (Acoustic)", " Live", "现场版", " (Remastered)")) {
            val studio = candidate(1, title)
            val version = candidate(2, title + suffix)
            assertNull(accept(local(title), listOf(version)), suffix)
            assertNull(accept(local(title + suffix), listOf(studio)), suffix)
            assertEquals(studio, accept(local(title), listOf(version, studio)), suffix)
        }
    }

    @Test
    fun ordinaryWordsDoNotBecomeVersionAnnotations() {
        for ((title, version) in listOf("Live Forever With Me Tonight" to "Live", "Olive Trees In The Wind" to "Live",
            "Democracy Is Here To Stay" to "Demo", "Acoustic Dreams Of Tomorrow" to "Acoustic")) {
            val remote = candidate(1, title)
            assertEquals(remote, accept(local(title), listOf(remote)))
            assertNull(accept(local(title), listOf(remote.copy(title = "$title ($version)"))))
        }
    }

    @Test
    fun incompleteOrDisjointArtistsAreNotAutomatic() {
        val track = local("Song").copy(artists = listOf("Artist", "Guest"))
        assertNull(accept(track, listOf(candidate(1, "Song"))))
        val duet = candidate(2, "Song").copy(artists = listOf("Guest", "Artist"))
        assertEquals(duet, accept(track, listOf(duet)))
        assertNull(accept(local("Song"), listOf(duet.copy(artists = emptyList()))))
        assertNull(accept(local("Song").copy(artists = listOf("Artist One")),
            listOf(candidate(3, "Song").copy(artists = listOf("Artist Two")))))
    }

    @Test
    fun duplicateRowsAndDifferentReleasesDoNotCreateAFalseRecordingConflict() {
        val first = candidate(1, "Song")
        val otherSource = first.copy(source = MusicSource.QQ)
        assertEquals(first, accept(local("Song"), listOf(first, first, otherSource)))
        val decision = RecordingMatch.decide(local("Song").copy(album = null),
            found(listOf(first, otherSource.copy(album = "Other Album"))), null).outcome as MatchOutcome.Accept
        assertEquals(first, decision.candidate)
        assertNull(decision.release)
        val matched = RecordingMatch.decide(local("Song"),
            found(listOf(first, otherSource.copy(album = "Other Album"))), null).outcome as MatchOutcome.Accept
        assertEquals("Album", matched.release?.album)
    }

    @Test
    fun blankTextAndNonPositiveDurationsDoNotCountAsMatchingEvidence() {
        val remote = candidate(1, "---").copy(artists = listOf(" "), album = "", durationMs = 0)
        val decision = RecordingMatch.decide(LocalTrack(".mp3", " ", emptyList(), " ", 0), found(listOf(remote)), null)
        assertTrue(decision.outcome !is MatchOutcome.Accept)
        assertFalse(sameRecording(remote, remote))
        assertTrue(RecordingMatch.decide(local("Song").copy(durationMs = 0), found(listOf(candidate(2, "Song"))), null)
            .ranked.single().explanations.contains("时长未知"))
    }
}
