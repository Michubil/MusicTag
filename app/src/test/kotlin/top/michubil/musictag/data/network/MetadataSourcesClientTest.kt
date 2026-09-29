package top.michubil.musictag.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.MatchSelection
import top.michubil.musictag.data.match.PreparedScrape
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.match.MatchResolver
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.match.UserQuery
import top.michubil.musictag.data.match.planWrite
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.sourceSelections

class MetadataSourcesClientTest {
    private val track = LocalTrack("title.mp3", "title", listOf("artist"), "album", 1000L)

    @Test
    fun downloadUsesTheFixedSelectionEvenWhenAnotherCandidateRanksFirst() = runBlocking {
        val chosen = song(MusicSource.NETEASE)
        val other = chosen.copy(id = chosen.id + 1, title = "Another song")
        val client = object : MusicSourceClient {
            override val source = MusicSource.NETEASE
            override suspend fun search(query: String, page: Int): SearchPage = error("Download must not search")
            override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata {
                assertEquals(chosen, candidate)
                return ScrapedMetadata(title = RemoteValue.Available(candidate.title))
            }
        }
        val selection = MatchSelection(MatchOutcome.Accept(chosen, chosen, "fixed"), listOf(other, chosen), manual = false)
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.NETEASE)))
        val result = MetadataSourcesClient(client).metadata(track, options, selection)
        assertEquals(RemoteValue.Available(chosen.title), result.metadata.title)
    }

    private class StubClient(
        override val source: MusicSource,
        private val find: suspend (String) -> List<SongCandidate>,
        private val data: ScrapedMetadata = ScrapedMetadata(title = RemoteValue.Available("title")),
        override val supportedFields: Set<MetadataField> = MetadataField.entries.toSet(),
    ) : MusicSourceClient {
        var searches = 0
        var downloads = 0
        val requestedFields = mutableListOf<Set<MetadataField>>()
        override suspend fun search(query: String, page: Int): SearchPage {
            searches++
            return SearchPage(find(query))
        }
        override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata {
            downloads++
            requestedFields += fields
            return data
        }
    }

    @Test
    fun qqOnlyDoesNotReportPartialForUnsupportedDisc() = runBlocking {
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            supportedFields = MetadataField.entries.toSet() - MetadataField.DISC)
        val options = ScrapeOptions(
            policies = setOf(MetadataField.TITLE, MetadataField.DISC).associateWith { FieldPolicy() },
            sources = ScrapeSources(sourceSelections(MusicSource.QQ)),
        )
        val prepared = MetadataSourcesClient(qq).prepare(track, options, null)
        assertNull(prepared.stop)
        assertEquals(setOf(MetadataField.DISC), prepared.unsupported)
        assertEquals(listOf(setOf(MetadataField.TITLE)), qq.requestedFields)
        assertEquals(ScrapeKind.COMPLETE,
            planWrite(prepared.metadata, emptyMap(), false, options, prepared.kept, prepared.unsupported).disposition.kind)
    }

    @Test
    fun anotherReleaseCannotSupplyQqDiscButSameReleaseFailureIsPartial() = runBlocking {
        val qqSong = song(MusicSource.QQ)
        val qq = StubClient(MusicSource.QQ, { error("Manual selection reuses the QQ candidate") },
            supportedFields = MetadataField.entries.toSet() - MetadataField.DISC)
        val options = ScrapeOptions(policies = setOf(MetadataField.TITLE, MetadataField.DISC)
            .associateWith { FieldPolicy() })
        val otherRelease = StubClient(MusicSource.NETEASE,
            { listOf(song(MusicSource.NETEASE).copy(album = "other album")) })
        val unmatched = MetadataSourcesClient(qq, otherRelease).prepare(track, options, qqSong)
        assertEquals(setOf(MetadataField.DISC), unmatched.unsupported)
        assertEquals(ScrapeKind.COMPLETE,
            planWrite(unmatched.metadata, emptyMap(), false, options, unmatched.kept, unmatched.unsupported).disposition.kind)

        val sameRelease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val missing = MetadataSourcesClient(qq, sameRelease).prepare(track, options, qqSong)
        assertEquals(emptySet<MetadataField>(), missing.unsupported)
        assertEquals(ScrapeKind.PARTIAL,
            planWrite(missing.metadata, emptyMap(), false, options, missing.kept, missing.unsupported).disposition.kind)
    }

    @Test
    fun bothSearchesStartBeforeEitherCompletesEvenWithAnExactMatch() = runBlocking {
        val started = mutableSetOf<MusicSource>()
        val bothStarted = CompletableDeferred<Unit>()
        val clients = MusicSource.entries.map { source ->
            StubClient(source, {
                started += source
                if (started.size == 2) bothStarted.complete(Unit)
                bothStarted.await()
                listOf(song(source))
            })
        }
        val results = withTimeout(5000) {
            MetadataSourcesClient(*clients.toTypedArray()).candidates(track, ScrapeOptions())
        }
        assertEquals(MusicSource.entries.toSet(), results.ranked.map(SongCandidate::source).toSet())
        assertTrue(results.outcome is MatchOutcome.Accept)
    }

    @Test
    fun aSourcesFallbackDoesNotWaitForTheOtherSourcesFirstResponse() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        val netease = StubClient(MusicSource.NETEASE, {
            fallbackStarted.await()
            listOf(song(MusicSource.NETEASE))
        })
        val qq = StubClient(MusicSource.QQ, { query ->
            if (query == "title") {
                fallbackStarted.complete(Unit)
                listOf(song(MusicSource.QQ))
            } else emptyList()
        })
        val result = withTimeout(5_000) {
            MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        }
        assertEquals(MusicSource.entries.toSet(), result.ranked.map { it.source }.toSet())
        assertEquals(1, netease.searches)
        assertEquals(2, qq.searches)
    }

    @Test
    fun repeatedCandidatesKeepTheirOrderAndShareTheDetailBudgetAcrossQueries() = runBlocking {
        val detailed = mutableListOf<Long>()
        val candidates = (1L..5L).map { id ->
            song(MusicSource.QQ).copy(id = id, artists = listOf("Other artist"), durationMs = null)
        }
        val source = object : MusicSourceClient {
            override val source = MusicSource.QQ
            override suspend fun search(query: String, page: Int) = SearchPage(candidates + candidates)
            override suspend fun enrich(candidate: SongCandidate): SongCandidate {
                detailed += candidate.id
                return candidate.copy(durationMs = track.durationMs)
            }
            override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>) = ScrapedMetadata()
        }
        val options = ScrapeOptions(sources = ScrapeSources(sourceSelections(MusicSource.QQ)))
        val result = MetadataSourcesClient(source).candidates(track, options)
        assertEquals(listOf(1L, 2L, 3L), detailed)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.ranked.map { it.id })
    }

    @Test
    fun candidatesAreRankedTogetherAndDeduplicatedWithinEachSource() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE).copy(title = "different")) })
        val qqSong = song(MusicSource.QQ)
        val qq = StubClient(MusicSource.QQ, { listOf(qqSong, qqSong, qqSong.copy(id = 2, title = "title live")) })
        val results = MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        assertEquals(3, results.ranked.size)
        assertEquals(qqSong.key, results.ranked.first().key)
    }

    @Test
    fun strongerQqCandidateFoundBySecondQuerySuppliesTheMetadata() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)
            .copy(album = "Other Album", durationMs = null)) })
        val qq = StubClient(MusicSource.QQ, { query ->
            if (query == "title") listOf(song(MusicSource.QQ)) else emptyList()
        }, ScrapedMetadata(title = RemoteValue.Available("QQ title")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val client = MetadataSourcesClient(netease, qq)

        val search = client.candidates(track, options)
        assertEquals(MusicSource.QQ, search.ranked.first().source)
        assertEquals(2, qq.searches)
        assertEquals(1, netease.searches)
        assertEquals(MusicSource.QQ, (search.outcome as MatchOutcome.Accept).candidate.source)

        val automatic = client.prepare(track, options, null, search)
        assertEquals(RemoteValue.Available("QQ title"), automatic.metadata.title)
        assertEquals(0, netease.downloads)
        assertEquals(1, qq.downloads)
    }

    @Test
    fun fingerprintStillSearchesAfterWrongTextWasAcceptedOrTextHasNoUsableInformation() = runBlocking {
        val wrongSong = song(MusicSource.NETEASE).copy(title = "Wrong song", artists = listOf("Wrong artist"))
        val correct = song(MusicSource.NETEASE).copy(id = 2, title = "Correct song", artists = listOf("Singer"))
        val inputs = listOf(
            track.copy(fileName = "Wrong artist - Wrong song.mp3", title = wrongSong.title, artists = wrongSong.artists),
            track.copy(fileName = "Wrong artist - Wrong song.mp3", title = correct.title, artists = correct.artists),
            track.copy(fileName = "Singer - Correct song.mp3", title = wrongSong.title, artists = wrongSong.artists),
            track.copy(fileName = "", title = null, artists = emptyList(), album = null),
            track.copy(fileName = "random.mp3", title = "Unknown", artists = emptyList(), album = null),
        )
        for (local in inputs) {
            val queries = mutableListOf<String>()
            val source = StubClient(MusicSource.NETEASE, { query ->
                queries += query
                when {
                    "Correct song" in query -> listOf(correct)
                    "Wrong song" in query -> listOf(wrongSong)
                    else -> emptyList()
                }
            })
            val client = MetadataSourcesClient(source)
            val options = ScrapeOptions(sources = ScrapeSources(sourceSelections(MusicSource.NETEASE)))
            var recognized = false
            val resolved = MatchResolver.resolve(local,
                search = { client.candidates(local, options, it) },
                recognize = {
                    recognized = true
                    listOf(FingerprintSuggestion(correct.title, correct.artists, 0.9))
                })
            assertTrue(recognized)
            assertEquals(correct, (resolved.outcome as MatchOutcome.Accept).candidate)
            assertTrue(queries.any { "Correct song" in it })
            assertTrue(queries.count { it == "Correct song Singer" } <= 1)
        }
    }

    @Test
    fun emptyQqResultsRetrySimplifiedTitleAndKeepConflictingArtistForManualChoice() = runBlocking {
        val local = track.copy(title = "月華の円舞曲 -Valse di Fantastica-", artists = listOf("宮野幸子", "森下唯"))
        val searched = mutableListOf<String>()
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qqSong = song(MusicSource.QQ).copy(title = "月華の円舞曲", artists = listOf("下村陽子"))
        val qq = StubClient(MusicSource.QQ, { query ->
            searched += query
            if (query == "月華の円舞曲") listOf(qqSong) else emptyList()
        })

        val results = MetadataSourcesClient(netease, qq).candidates(local, ScrapeOptions())

        assertTrue(searched.contains("月華の円舞曲"))
        assertTrue(qqSong in results.ranked)
        assertTrue(results.ranked.any { it.source == MusicSource.NETEASE })
        assertTrue(results.outcome is MatchOutcome.Review)
        assertEquals("署名不同", (results.outcome as MatchOutcome.Review).summary)
    }

    @Test
    fun resolverFallbackIsDownloadedWithoutRepeatingSearch() = runBlocking {
        val local = track.copy(artists = listOf("Different artist"))
        val source = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.NETEASE)))
        val client = MetadataSourcesClient(source)
        val search = client.candidates(local, options)
        assertTrue(search.outcome is MatchOutcome.Review)

        val searches = source.searches
        val selection = requireNotNull(MatchResolver.select(local, search))
        val prepared = client.metadata(local, options, selection)

        assertEquals(searches, source.searches)
        assertNull(prepared.stop)
        assertEquals(RemoteValue.Available("title"), prepared.metadata.title)
    }

    @Test
    fun unrelatedNonemptyResultDoesNotStopOtherSourceTitleFallback() = runBlocking {
        val local = track.copy(title = "title -long subtitle-")
        val searched = mutableListOf<String>()
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE).copy(title = "unrelated")) })
        val qq = StubClient(MusicSource.QQ, { query ->
            searched += query
            if (query == "title") listOf(song(MusicSource.QQ).copy(title = local.title!!)) else emptyList()
        })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))

        val result = MetadataSourcesClient(netease, qq).prepare(local, options, null)

        assertTrue(searched.contains("title"))
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertTrue(netease.searches > 1)
        assertEquals(1, qq.downloads)
    }

    @Test
    fun singleSourceSelectionDoesNotQueryTheOtherSource() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { error("Must not query disabled source") })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        val options = ScrapeOptions(
            policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.QQ)),
        )
        assertEquals(1, MetadataSourcesClient(netease, qq).candidates(track, options).ranked.size)
        assertEquals(0, netease.searches)
        assertEquals(1, qq.searches)
    }

    @Test
    fun failedArtistSearchStillAttemptsTheDistinctTitleQuery() = runBlocking {
        val local = LocalTrack("月夜に謳う君.mp3", "月夜に謳う君 -LUNA-", listOf("宮野幸子", "森下唯"), null, null)
        val artistQuery = "月夜に謳う君 -LUNA- 宮野幸子 森下唯"
        val titleQuery = "月夜に謳う君 -LUNA-"
        val searched = mutableListOf<String>()
        val qq = StubClient(MusicSource.QQ, { query ->
            searched += query
            if (query == artistQuery) throw IllegalStateException("QQ search temporarily unavailable")
            if (query == titleQuery) listOf(song(MusicSource.QQ).copy(title = titleQuery,
                artists = local.artists)) else emptyList()
        })
        val options = ScrapeOptions(
            policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.QQ)),
        )

        val result = MetadataSourcesClient(qq).candidates(local, options)

        assertEquals(listOf(artistQuery, titleQuery), searched)
        assertEquals(MusicSource.QQ, result.ranked.single().source)
    }

    @Test
    fun confirmedNextPagesContinueUntilTheSourceEnds() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val qq = object : MusicSourceClient {
            override val source = MusicSource.QQ
            override suspend fun search(query: String, page: Int): SearchPage {
                requestedPages += page
                return when (page) {
                    0 -> SearchPage(emptyList(), nextPage = 1)
                    1 -> SearchPage(emptyList(), nextPage = 2)
                    else -> SearchPage(listOf(song(MusicSource.QQ)))
                }
            }
            override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>) = ScrapedMetadata()
        }
        val options = ScrapeOptions(
            policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.QQ)),
        )
        val result = MetadataSourcesClient(qq).candidates(track, options)
        assertTrue(requestedPages.containsAll(listOf(0, 1, 2)))
        assertEquals(1, result.ranked.size)
    }

    @Test
    fun oneSourceFailureDoesNotDiscardTheOtherSourcesCandidates() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { throw IllegalStateException("Unavailable") })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        val results = MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        assertEquals(listOf(MusicSource.QQ), results.ranked.map(SongCandidate::source))
        assertTrue(results.outcome is MatchOutcome.Accept)
    }

    @Test
    fun failedSearchWithoutAnyCandidateIsReported() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { throw IllegalStateException("Unavailable") })
        val qq = StubClient(MusicSource.QQ, { emptyList() })
        val results = MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        assertTrue(results.ranked.isEmpty())
        assertTrue(results.outcome is MatchOutcome.None)
        assertEquals("Unavailable", results.outcome.summary)
    }

    @Test
    fun cancellationIsNotConvertedToPartialSearchSuccess() {
        val netease = StubClient(MusicSource.NETEASE, { throw CancellationException("cancel") })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        assertThrows(CancellationException::class.java) {
            runBlocking { MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions()) }
        }
    }

    @Test
    fun automaticScrapingSearchesBothSourcesOnceBeforeDownloadingPreferredFields() = runBlocking {
        val started = mutableSetOf<MusicSource>()
        val gate = CompletableDeferred<Unit>()
        val clients = MusicSource.entries.map { source ->
            StubClient(source, {
                started += source
                if (started.size == 2) gate.complete(Unit)
                gate.await()
                listOf(song(source))
            })
        }
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = withTimeout(5000) {
            MetadataSourcesClient(*clients.toTypedArray()).prepare(track, options, null)
        }
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(listOf(1, 1), clients.map { it.searches })
        assertEquals(listOf(1, 0), clients.map { it.downloads })
    }

    @Test
    fun fingerprintRecoveredSearchWritesPlatformFieldsWithoutRepeatingSearch() = runBlocking {
        val wrong = track.copy(fileName = "random.wav", title = "Wrong title", artists = listOf("Wrong artist"),
            album = "Wrong album")
        val candidate = song(MusicSource.NETEASE).copy(title = "Correct song", artists = listOf("Singer"),
            album = "Correct album")
        val source = StubClient(MusicSource.NETEASE, { query ->
            if (query.contains("Correct song")) listOf(candidate) else emptyList()
        }, ScrapedMetadata(
            title = RemoteValue.Available(candidate.title),
            artists = RemoteValue.Available(candidate.artists),
            album = RemoteValue.Available(candidate.album),
        ))
        val client = MetadataSourcesClient(source)
        val options = ScrapeOptions(
            policies = setOf(MetadataField.TITLE, MetadataField.ARTISTS, MetadataField.ALBUM)
                .associateWith { FieldPolicy() },
            sources = ScrapeSources(sourceSelections(MusicSource.NETEASE)),
        )
        val query = UserQuery(candidate.title, candidate.artists)
        val search = client.candidates(wrong, options, query)
        val searches = source.searches

        val result = client.prepare(wrong, options, null, search)

        assertTrue(search.outcome is MatchOutcome.Accept)
        assertEquals(RemoteValue.Available(candidate.title), result.metadata.title)
        assertEquals(RemoteValue.Available(candidate.artists), result.metadata.artists)
        assertEquals(RemoteValue.Available(candidate.album), result.metadata.album)
        assertEquals(searches, source.searches)
    }

    @Test
    fun parallelSearchStillRejectsConflictingVersionsForFallbackFields() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(title = "title (Live)", album = "another album")) },
            ScrapedMetadata(lyrics = RemoteValue.Available("Wrong recording")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(RemoteValue.Unavailable, result.metadata.lyrics)
        assertTrue(qq.searches > 1)
        assertEquals(0, qq.downloads)
    }

    @Test
    fun forcedCandidateIsReusedWhileOtherEnabledSourcesAreSearched() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { error("Already selected manually") })
        val qq = StubClient(MusicSource.QQ, { query ->
            assertEquals("title artist", query)
            listOf(song(MusicSource.QQ))
        })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, song(MusicSource.NETEASE))
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(0, netease.searches)
        assertEquals(1, qq.searches)
    }

    @Test
    fun sharedRecordingUsesStrongerReleaseEvidenceForFields() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE,
            { listOf(song(MusicSource.NETEASE).copy(album = "XYZ")) },
            ScrapedMetadata(title = RemoteValue.Available("NetEase title"), album = RemoteValue.Available("XYZ")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("QQ title"), album = RemoteValue.Available("album")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.ALBUM to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("QQ title"), result.metadata.title)
        assertEquals(RemoteValue.Available("album"), result.metadata.album)
        assertEquals(listOf(1, 1), listOf(netease.searches, qq.searches))
        assertEquals(listOf(0, 1), listOf(netease.downloads, qq.downloads))
    }

    @Test
    fun differentReleasesDoNotBlockTheRecordingTitle() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(album = "another album")) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track.copy(album = null), options, null)
        assertNull(result.stop)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
    }

    @Test
    fun localAlbumSelectsTheReleaseWithoutBlockingTheTitle() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(album = "another album")) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
    }

    @Test
    fun manualSelectionKeepsAnotherReleaseOutOfReleaseFields() = runBlocking {
        val chosen = song(MusicSource.NETEASE)
        val netease = StubClient(MusicSource.NETEASE, { error("Already selected manually") },
            ScrapedMetadata(title = RemoteValue.Available("title")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(album = "another album")) },
            ScrapedMetadata(album = RemoteValue.Available("another album"), lyrics = RemoteValue.Available("Same recording")))
        val options = ScrapeOptions(policies = mapOf(
            MetadataField.TITLE to FieldPolicy(), MetadataField.ALBUM to FieldPolicy(), MetadataField.LYRICS to FieldPolicy(),
        ))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, chosen)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(RemoteValue.Unavailable, result.metadata.album)
        assertEquals(RemoteValue.Available("Same recording"), result.metadata.lyrics)
    }

    @Test
    fun aWeakNearTitleDoesNotHideTheReliableCandidate() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, {
            listOf(song(MusicSource.NETEASE), song(MusicSource.NETEASE).copy(id = 2, title = "titles"))
        })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(1, netease.downloads)
    }

    @Test
    fun selectedSongSourceSuppliesTagsAndLyricsTogether() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE,
            { listOf(song(MusicSource.NETEASE).copy(album = "Other Album")) },
            ScrapedMetadata(title = RemoteValue.Available("NetEase title"), lyrics = RemoteValue.Available("NetEase lyrics")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("QQ title"), lyrics = RemoteValue.Available("QQ lyrics")))
        val options = ScrapeOptions(
            policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy()),
            sources = ScrapeSources(sourceSelections(MusicSource.NETEASE, MusicSource.QQ)),
        )
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("QQ title"), result.metadata.title)
        assertEquals(RemoteValue.Available("QQ lyrics"), result.metadata.lyrics)
        assertEquals(listOf(0, 1), listOf(netease.downloads, qq.downloads))
    }

    @Test
    fun confirmedMissingLyricsRemainConfirmedWhenOtherSourceHasNone() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("title"), lyrics = RemoteValue.ConfirmedAbsent))
        val options = ScrapeOptions(policies = mapOf(
            MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy(),
        ))

        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)

        assertEquals(RemoteValue.ConfirmedAbsent, result.metadata.lyrics)
    }

    @Test
    fun confirmedMissingLyricsYieldToLyricsFromTheSameRecording() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) },
            ScrapedMetadata(title = RemoteValue.Available("title"), lyrics = RemoteValue.ConfirmedAbsent))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(lyrics = RemoteValue.Available("Actual lyrics")))
        val options = ScrapeOptions(policies = setOf(MetadataField.TITLE, MetadataField.LYRICS)
            .associateWith { FieldPolicy() })

        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)

        assertEquals(RemoteValue.Available("Actual lyrics"), result.metadata.lyrics)
        assertEquals(listOf(setOf(MetadataField.LYRICS)), qq.requestedFields)
    }

    @Test
    fun oneUnavailableSourceDoesNotPreventReliableAutomaticMatching() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { error("Temporarily unavailable") })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(0, netease.downloads)
        assertEquals(1, qq.downloads)
    }

    @Test
    fun manualSelectionCanResolveAConflictWithoutSearchingItsSourceAgain() = runBlocking {
        val forced = song(MusicSource.QQ).copy(album = "Chosen release")
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { error("Already selected manually") },
            ScrapedMetadata(title = RemoteValue.Available("Chosen title")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track.copy(album = null), options, forced)
        assertEquals(RemoteValue.Available("Chosen title"), result.metadata.title)
        assertEquals(0, qq.searches)
        assertEquals(0, netease.downloads)
    }

    @Test
    fun crossSourceFallbackKeepsTheActualLocalDuration() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE,
            { listOf(song(MusicSource.NETEASE).copy(durationMs = 109_000L)) })
        val qq = StubClient(MusicSource.QQ,
            { listOf(song(MusicSource.QQ).copy(durationMs = 113_000L)) },
            ScrapedMetadata(lyrics = RemoteValue.Available("Wrong length")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).prepare(track.copy(durationMs = 100_000L), options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(RemoteValue.Unavailable, result.metadata.lyrics)
        assertEquals(0, qq.downloads)
    }

    @Test
    fun filenameIdentityIsUsedForAutomaticScrapingWithoutExtraSearches() = runBlocking {
        val clients = MusicSource.entries.map { source ->
            StubClient(source, { query ->
                assertEquals("artist - title", query)
                listOf(song(source))
            })
        }
        val fileTrack = LocalTrack("01. artist - title.mp3", null, emptyList(), null, 1000L)
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(*clients.toTypedArray()).prepare(fileTrack, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(listOf(1, 1), clients.map { it.searches })
    }

    @Test
    fun emptySelectionDoesNotSearchOrDownloadInEitherEntryPoint() = runBlocking {
        val clients = MusicSource.entries.map { source -> StubClient(source, { error("No fields selected") }) }
        val client = MetadataSourcesClient(*clients.toTypedArray())
        val options = ScrapeOptions(policies = emptyMap())
        assertTrue(client.candidates(track, options).ranked.isEmpty())
        val prepared = client.metadata(track, options, MatchSelection(
            MatchOutcome.Accept(song(MusicSource.NETEASE), null, "selected"), emptyList(), manual = false))
        assertEquals(ScrapedMetadata(), prepared.metadata)
        assertEquals(ScrapeKind.UNCHANGED, prepared.stop?.kind)
        assertEquals(listOf(0, 0), clients.map { it.searches })
    }

    @Test
    fun unavailableSelectedFieldsStopBeforeWriting() = runBlocking {
        val clients = MusicSource.entries.map { source -> StubClient(source,
            { listOf(song(source)) }, ScrapedMetadata()) }
        val options = ScrapeOptions(policies = mapOf(MetadataField.LYRICS to FieldPolicy()))

        val prepared = MetadataSourcesClient(*clients.toTypedArray()).prepare(track, options, null)

        assertEquals(ScrapeKind.FAILED, prepared.stop?.kind)
        assertEquals(ScrapedMetadata(), prepared.metadata)
        assertTrue(clients.all { it.requestedFields == listOf(setOf(MetadataField.LYRICS)) })
    }

    @Test
    fun disabledNetworkSourcesBlockScrapingBeforeAnyRequest() = runBlocking {
        val clients = MusicSource.entries.map { source -> StubClient(source, { error("Source is disabled") }) }
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()),
            sources = ScrapeSources(sourceSelections()))
        val metadata = MetadataSourcesClient(*clients.toTypedArray())

        assertTrue(metadata.candidates(track, options).outcome is MatchOutcome.None)
        assertEquals(ScrapeKind.FAILED, metadata.metadata(track, options, MatchSelection(
            MatchOutcome.Accept(song(MusicSource.NETEASE), null, "selected"), emptyList(), manual = false)).stop?.kind)
        assertEquals(listOf(0, 0), clients.map { it.searches })
    }

    private suspend fun MetadataSourcesClient.prepare(
        local: LocalTrack, options: ScrapeOptions, forced: SongCandidate?, prior: CandidateSearch? = null,
    ): PreparedScrape {
        val search = prior ?: if (forced == null) candidates(local, options) else relatedCandidates(local, options, forced)
        val selection = requireNotNull(MatchResolver.select(local, search, forced))
        return metadata(local, options, selection)
    }

    private fun song(source: MusicSource) = SongCandidate(
        1, "title", listOf("artist"), "album", null, 1000L, null, null, 1, source,
    )

    @Test
    fun manuallySelectedSourceRequestsTagsLyricsAndCoverTogether() = runBlocking {
        val cover = CoverImage(byteArrayOf(1), "image/jpeg", 1, 1)
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { error("Selected source must not be searched again") },
            ScrapedMetadata(title = RemoteValue.Available("title"),
                lyrics = RemoteValue.Available("Lyrics"), cover = RemoteValue.Available(cover)))
        val fields = setOf(MetadataField.TITLE, MetadataField.LYRICS, MetadataField.COVER)
        val options = ScrapeOptions(policies = fields.associateWith { FieldPolicy() })

        val result = MetadataSourcesClient(netease, qq).prepare(track, options, song(MusicSource.QQ))

        assertEquals(listOf(fields), qq.requestedFields)
        assertEquals(0, netease.downloads)
        assertEquals(0, qq.searches)
        assertEquals(RemoteValue.Available("Lyrics"), result.metadata.lyrics)
        assertEquals(RemoteValue.Available(cover), result.metadata.cover)
    }

    @Test
    fun missingLyricsFallBackToCompatibleSourceWithoutReplacingTitle() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) },
            ScrapedMetadata(title = RemoteValue.Available("Primary title")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("Other title"),
                lyrics = RemoteValue.Available("Lyrics")))
        val options = ScrapeOptions(policies = setOf(MetadataField.TITLE, MetadataField.LYRICS)
            .associateWith { FieldPolicy() })

        val result = MetadataSourcesClient(netease, qq).prepare(track, options, null)

        assertEquals(RemoteValue.Available("Primary title"), result.metadata.title)
        assertEquals(RemoteValue.Available("Lyrics"), result.metadata.lyrics)
        assertEquals(listOf(setOf(MetadataField.TITLE, MetadataField.LYRICS)), netease.requestedFields)
        assertEquals(listOf(setOf(MetadataField.LYRICS)), qq.requestedFields)
    }
}
