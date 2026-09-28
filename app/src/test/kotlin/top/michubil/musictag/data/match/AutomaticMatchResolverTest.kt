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

    private fun found(candidate: SongCandidate, query: UserQuery?): CandidateSearch {
        val decision = RecordingMatch.decide(track, listOf(FoundCandidate(candidate, 0, 0)), query)
        return CandidateSearch(decision.ranked, emptyList(), decision.outcome, query?.title.orEmpty(), "")
    }

    @Test
    fun fingerprintRecoversIdentityWhenTagsAndFilenameAreWrong() = runBlocking {
        val queries = mutableListOf<UserQuery?>()
        val result = AutomaticMatchResolver.resolve(
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
    fun acceptedTextMatchDoesNotCalculateFingerprint() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val decision = RecordingMatch.decide(acceptedTrack, listOf(FoundCandidate(correct, 0, 0)), null)
        val text = CandidateSearch(decision.ranked, emptyList(), decision.outcome, correct.title, "Singer")
        val result = AutomaticMatchResolver.resolve(
            search = { text },
            recognize = { error("Fingerprint must not be needed") },
        )

        assertTrue(result.search.outcome is MatchOutcome.Accept)
        assertNull(result.query)
    }
}
