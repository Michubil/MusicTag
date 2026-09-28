package top.michubil.musictag.data.match

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.network.FingerprintSuggestion

class AutomaticMatchResolverTest {
    private val track = LocalTrack("random.wav", "Wrong title", listOf("Wrong artist"), null, 180_000)
    private val correct = SongCandidate(1, "Correct song", listOf("Singer"), "Album", null,
        180_000, null, null, 1, MusicSource.NETEASE)

    private fun found(candidate: SongCandidate, query: UserQuery?, local: LocalTrack = track): CandidateSearch {
        val decision = RecordingMatch.decide(local, listOf(FoundCandidate(candidate, 0)), query)
        return CandidateSearch(decision.ranked, decision.outcome)
    }

    @Test
    fun fingerprintRecoversIdentityWhenTagsAndFilenameAreWrong() = runBlocking {
        val queries = mutableListOf<UserQuery?>()
        val result = AutomaticMatchResolver.resolve(
            track = track,
            search = { query ->
                queries += query
                found(correct, query)
            },
            recognize = { listOf(FingerprintSuggestion("Correct song", listOf("Singer"), 0.86)) },
        )

        assertEquals(listOf(null, UserQuery("Correct song", listOf("Singer"))), queries)
        assertEquals(correct, (result.search.outcome as MatchOutcome.Accept).candidate)
        assertEquals(queries.last(), result.query)
    }

    @Test
    fun competingFingerprintRecordingsNeedManualSelection() = runBlocking {
        val other = correct.copy(id = 2, title = "Different song")
        val result = AutomaticMatchResolver.resolve(
            track = track,
            search = { query -> found(if (query?.title == other.title) other else correct, query) },
            recognize = {
                listOf(
                    FingerprintSuggestion(correct.title, correct.artists, 0.91),
                    FingerprintSuggestion(other.title, other.artists, 0.89),
                )
            },
        )

        assertTrue(result.search.outcome is MatchOutcome.Review)
        assertNull(result.query)
    }

    @Test
    fun acceptedTextMatchIsCheckedAgainstAudio() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)
        var lookedUp = false
        val result = AutomaticMatchResolver.resolve(
            track = acceptedTrack,
            search = { text },
            recognize = {
                lookedUp = true
                listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9))
            },
        )

        assertTrue(lookedUp)
        assertTrue(result.search.outcome is MatchOutcome.Accept)
        assertNull(result.query)
    }

    @Test
    fun matchingWrongTagsAndFilenameAreCorrectedByFingerprint() = runBlocking {
        val wrong = correct.copy(title = "Wrong song", artists = listOf("Other singer"))
        val wrongTrack = track.copy(fileName = "Other singer - Wrong song.wav", title = wrong.title,
            artists = wrong.artists)
        val queries = mutableListOf<UserQuery?>()

        val result = AutomaticMatchResolver.resolve(
            track = wrongTrack,
            search = { query ->
                queries += query
                found(if (query == null) wrong else correct, query, wrongTrack)
            },
            recognize = { listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9)) },
        )

        assertEquals(listOf(null, UserQuery(correct.title, correct.artists)), queries)
        assertEquals(correct, (result.search.outcome as MatchOutcome.Accept).candidate)
        assertEquals(queries.last(), result.query)
    }

    @Test
    fun unavailableFingerprintKeepsTheAcceptedTextMatch() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)

        val result = AutomaticMatchResolver.resolve(
            track = acceptedTrack,
            search = { text },
            recognize = { error("AcoustID unavailable") },
        )

        assertEquals(text, result.search)
        assertNull(result.query)
    }

    @Test
    fun unavailableFingerprintCannotPromoteAnUnacceptedTextCandidate() = runBlocking {
        val queries = mutableListOf<UserQuery?>()
        val result = AutomaticMatchResolver.resolve(
            track = track,
            search = { query ->
                queries += query
                found(correct, query)
            },
            recognize = { error("AcoustID unavailable") },
        )

        assertEquals(listOf(null), queries)
        assertTrue(result.search.outcome is MatchOutcome.Review)
        assertNull(result.query)
    }

    @Test
    fun unconfirmedFingerprintConflictPreventsTextWrite() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)
        val other = correct.copy(id = 2, title = "Different song")

        val result = AutomaticMatchResolver.resolve(
            track = acceptedTrack,
            search = { query -> if (query == null) text else found(correct, query, acceptedTrack) },
            recognize = { listOf(FingerprintSuggestion(other.title, other.artists, 0.9)) },
        )

        assertTrue(result.search.outcome is MatchOutcome.Review)
        assertNull(result.query)
    }
}
