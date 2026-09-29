package top.michubil.musictag.data.match

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.network.FingerprintSuggestion

class MatchResolverTest {
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
        val result = MatchResolver.resolve(
            track = track,
            search = { query ->
                queries += query
                found(correct, query)
            },
            recognize = { listOf(FingerprintSuggestion("Correct song", listOf("Singer"), 0.86)) },
        )

        assertEquals(listOf<UserQuery?>(null), queries)
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun competingFingerprintRecordingsUseFirstHint() = runBlocking {
        val other = correct.copy(id = 2, title = "Different song")
        val result = MatchResolver.resolve(
            track = track,
            search = { query -> found(if (query?.title == other.title) other else correct, query) },
            recognize = {
                listOf(
                    FingerprintSuggestion(correct.title, correct.artists, 0.91),
                    FingerprintSuggestion(other.title, other.artists, 0.89),
                )
            },
        )

        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun earlierHintWinsAndCancelsAnUnneededSearch() = runBlocking {
        val lowerStarted = CompletableDeferred<Unit>()
        var lowerCancelled = false
        val result = withTimeout(5_000) {
            MatchResolver.resolve(
                track = track,
                search = { query ->
                    when (query?.title) {
                        null -> CandidateSearch(emptyList(), MatchOutcome.None("没有合适候选"))
                        correct.title -> {
                            lowerStarted.await()
                            found(correct, query)
                        }
                        else -> try {
                            lowerStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            lowerCancelled = true
                        }
                    }
                },
                recognize = { listOf(
                    FingerprintSuggestion(correct.title, correct.artists, 0.91),
                    FingerprintSuggestion("Other song", correct.artists, 0.89),
                ) },
            )
        }
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
        assertTrue(lowerCancelled)
    }

    @Test
    fun fasterLowerPriorityHintDoesNotReplaceEarlierHint() = runBlocking {
        val lowerCompleted = CompletableDeferred<Unit>()
        val other = correct.copy(id = 2, title = "Other song")
        val result = withTimeout(5_000) {
            MatchResolver.resolve(
                track = track,
                search = { query ->
                    when (query?.title) {
                        null -> CandidateSearch(emptyList(), MatchOutcome.None("没有合适候选"))
                        correct.title -> {
                            lowerCompleted.await()
                            found(correct, query)
                        }
                        else -> found(other, query).also { lowerCompleted.complete(Unit) }
                    }
                },
                recognize = { listOf(
                    FingerprintSuggestion(correct.title, correct.artists, 0.91),
                    FingerprintSuggestion(other.title, other.artists, 0.89),
                ) },
            )
        }
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun fingerprintReusesANonSelectedTextCandidate() = runBlocking {
        val wrong = correct.copy(id = 2, title = track.title!!, artists = track.artists)
        val queries = mutableListOf<UserQuery?>()
        val text = CandidateSearch(listOf(wrong, correct), MatchOutcome.Accept(wrong, wrong, "文字匹配"))
        val result = MatchResolver.resolve(
            track = track,
            search = { query -> queries += query; text },
            recognize = { listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9)) },
        )
        assertEquals(listOf<UserQuery?>(null), queries)
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
        assertEquals(text.ranked, result.ranked)
    }

    @Test
    fun acceptedTextMatchIsCheckedAgainstAudio() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)
        var lookedUp = false
        val result = MatchResolver.resolve(
            track = acceptedTrack,
            search = { text },
            recognize = {
                lookedUp = true
                listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9))
            },
        )

        assertTrue(lookedUp)
        assertTrue(result.outcome is MatchOutcome.Accept)
    }

    @Test
    fun matchingWrongTagsAndFilenameAreCorrectedByFingerprint() = runBlocking {
        val wrong = correct.copy(title = "Wrong song", artists = listOf("Other singer"))
        val wrongTrack = track.copy(fileName = "Other singer - Wrong song.wav", title = wrong.title,
            artists = wrong.artists)
        val queries = mutableListOf<UserQuery?>()

        val result = MatchResolver.resolve(
            track = wrongTrack,
            search = { query ->
                queries += query
                found(if (query == null) wrong else correct, query, wrongTrack)
            },
            recognize = { listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9)) },
        )

        assertEquals(listOf(null, UserQuery(correct.title, correct.artists)), queries)
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun fingerprintChoosesPlatformCandidateWhenTextAndPlatformResultsConflict() = runBlocking {
        val wrong = correct.copy(id = 3, title = "Wrong song", artists = listOf("Other singer"))
        val competing = correct.copy(id = 2, artists = listOf("Singer", "Guest"))
        val wrongTrack = track.copy(fileName = "Other singer - Wrong song.wav", title = wrong.title,
            artists = wrong.artists)
        val query = UserQuery(correct.title, correct.artists)
        val fingerprintSearch = RecordingMatch.decide(wrongTrack,
            listOf(FoundCandidate(correct, 0), FoundCandidate(competing, 1)), query)
        assertTrue(fingerprintSearch.outcome is MatchOutcome.Review)

        val result = MatchResolver.resolve(
            track = wrongTrack,
            search = { if (it == null) found(wrong, null, wrongTrack) else
                CandidateSearch(fingerprintSearch.ranked, fingerprintSearch.outcome) },
            recognize = { listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9)) },
        )

        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun unavailableFingerprintKeepsTheAcceptedTextMatch() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)

        val result = MatchResolver.resolve(
            track = acceptedTrack,
            search = { text },
            recognize = { error("AcoustID unavailable") },
        )

        assertEquals(text, result)
    }

    @Test
    fun unavailableFingerprintUsesFirstTextCandidate() = runBlocking {
        val queries = mutableListOf<UserQuery?>()
        val result = MatchResolver.resolve(
            track = track,
            search = { query ->
                queries += query
                found(correct, query)
            },
            recognize = { error("AcoustID unavailable") },
        )

        assertEquals(listOf(null), queries)
        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun unconfirmedFingerprintConflictKeepsTextChoice() = runBlocking {
        val acceptedTrack = track.copy(title = correct.title, artists = correct.artists)
        val text = found(correct, null, acceptedTrack)
        val other = correct.copy(id = 2, title = "Different song")

        val result = MatchResolver.resolve(
            track = acceptedTrack,
            search = { query -> if (query == null) text else found(correct, query, acceptedTrack) },
            recognize = { listOf(FingerprintSuggestion(other.title, other.artists, 0.9)) },
        )

        assertEquals(correct, (result.outcome as MatchOutcome.Accept).candidate)
    }

    @Test
    fun noPlatformCandidateReturnsNoMatchWithoutReview() = runBlocking {
        val result = MatchResolver.resolve(track,
            search = { CandidateSearch(emptyList(), MatchOutcome.None("没有合适候选")) },
            recognize = { emptyList() })

        assertTrue(result.outcome is MatchOutcome.None)
    }
}
