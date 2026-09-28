package top.michubil.musictag.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.match.SourceStatus
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.sourceSelections

class MetadataSourcesClientTest {
    private val track = LocalTrack("title.mp3", "title", listOf("artist"), "album", 1000L)

    private class StubClient(
        override val source: MusicSource,
        private val find: suspend (String) -> List<SongCandidate>,
        private val data: ScrapedMetadata = ScrapedMetadata(title = RemoteValue.Available("title")),
    ) : MusicSourceClient {
        var searches = 0
        var downloads = 0
        override suspend fun search(query: String, page: Int): SearchPage {
            searches++
            return SearchPage(find(query))
        }
        override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata {
            downloads++
            return data
        }
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
        assertEquals(MusicSource.entries.toSet(), results.ranked.map { it.candidate.source }.toSet())
        assertTrue(results.outcome is MatchOutcome.Accept)
    }

    @Test
    fun candidatesAreRankedTogetherAndDeduplicatedWithinEachSource() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE).copy(title = "different")) })
        val qqSong = song(MusicSource.QQ)
        val qq = StubClient(MusicSource.QQ, { listOf(qqSong, qqSong, qqSong.copy(id = 2, title = "title live")) })
        val results = MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        assertEquals(3, results.ranked.size)
        assertEquals(qqSong.key, results.ranked.first().candidate.key)
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
        assertTrue(results.ranked.any { it.candidate == qqSong })
        assertTrue(results.reports.any { it.source == MusicSource.NETEASE && it.candidateCount > 0 })
        assertTrue(results.outcome is MatchOutcome.Review)
        assertEquals("署名不同", (results.outcome as MatchOutcome.Review).summary)
    }

    @Test
    fun simplifiedTitleFallbackAlsoFeedsAutomaticScrapingWithoutRepeatingNonemptySearches() = runBlocking {
        val local = track.copy(title = "title -long subtitle-")
        val searched = mutableListOf<String>()
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE).copy(title = "unrelated")) })
        val qq = StubClient(MusicSource.QQ, { query ->
            searched += query
            if (query == "title") listOf(song(MusicSource.QQ).copy(title = local.title!!)) else emptyList()
        })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))

        val result = MetadataSourcesClient(netease, qq).metadata(local, options, null)

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
            sources = ScrapeSources(tags = sourceSelections(MusicSource.QQ)),
        )
        assertEquals(1, MetadataSourcesClient(netease, qq).candidates(track, options).ranked.size)
        assertEquals(0, netease.searches)
        assertEquals(1, qq.searches)
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
            sources = ScrapeSources(tags = sourceSelections(MusicSource.QQ)),
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
        assertEquals(listOf(MusicSource.QQ), results.ranked.map { it.candidate.source })
        assertEquals(SourceStatus.FAILED, results.reports.first { it.source == MusicSource.NETEASE }.status)
    }

    @Test
    fun failedSearchWithoutAnyCandidateIsReported() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { throw IllegalStateException("Unavailable") })
        val qq = StubClient(MusicSource.QQ, { emptyList() })
        val results = MetadataSourcesClient(netease, qq).candidates(track, ScrapeOptions())
        assertTrue(results.ranked.isEmpty())
        assertEquals(SourceStatus.FAILED, results.reports.first { it.source == MusicSource.NETEASE }.status)
        assertTrue(results.outcome is MatchOutcome.None)
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
            MetadataSourcesClient(*clients.toTypedArray()).metadata(track, options, null)
        }
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(listOf(1, 1), clients.map { it.searches })
        assertEquals(listOf(1, 0), clients.map { it.downloads })
    }

    @Test
    fun parallelSearchStillRejectsConflictingVersionsForFallbackFields() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(title = "title (Live)", album = "another album")) },
            ScrapedMetadata(lyrics = RemoteValue.Available("Wrong recording")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(RemoteValue.Unavailable, result.metadata.lyrics)
        assertEquals(1, qq.searches)
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
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, song(MusicSource.NETEASE))
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(0, netease.searches)
        assertEquals(1, qq.searches)
    }

    @Test
    fun sharedRecordingUsesSourceOrderForTitleAndLocalAlbumForTheRelease() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE,
            { listOf(song(MusicSource.NETEASE).copy(album = "XYZ")) },
            ScrapedMetadata(title = RemoteValue.Available("NetEase title"), album = RemoteValue.Available("XYZ")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("QQ title"), album = RemoteValue.Available("album")))
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.ALBUM to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
        assertEquals(RemoteValue.Available("NetEase title"), result.metadata.title)
        assertEquals(RemoteValue.Available("album"), result.metadata.album)
        assertEquals(listOf(1, 1), listOf(netease.searches, qq.searches))
    }

    @Test
    fun differentReleasesDoNotBlockTheRecordingTitle() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(album = "another album")) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).metadata(track.copy(album = null), options, null)
        assertEquals(ScrapeKind.COMPLETE, result.disposition.kind)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
    }

    @Test
    fun localAlbumSelectsTheReleaseWithoutBlockingTheTitle() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ).copy(album = "another album")) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
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
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, chosen)
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
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(1, netease.downloads)
    }

    @Test
    fun corroboratedIdentityStillUsesEachGroupsConfiguredSource() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { listOf(song(MusicSource.NETEASE)) },
            ScrapedMetadata(title = RemoteValue.Available("NetEase title"), lyrics = RemoteValue.Available("NetEase lyrics")))
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) },
            ScrapedMetadata(title = RemoteValue.Available("QQ title"), lyrics = RemoteValue.Available("QQ lyrics")))
        val options = ScrapeOptions(
            policies = mapOf(MetadataField.TITLE to FieldPolicy(), MetadataField.LYRICS to FieldPolicy()),
            sources = ScrapeSources(lyrics = sourceSelections(MusicSource.QQ, MusicSource.NETEASE)),
        )
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
        assertEquals(RemoteValue.Available("NetEase title"), result.metadata.title)
        assertEquals(RemoteValue.Available("QQ lyrics"), result.metadata.lyrics)
        assertEquals(listOf(1, 1), listOf(netease.downloads, qq.downloads))
    }

    @Test
    fun oneUnavailableSourceDoesNotPreventReliableAutomaticMatching() = runBlocking {
        val netease = StubClient(MusicSource.NETEASE, { error("Temporarily unavailable") })
        val qq = StubClient(MusicSource.QQ, { listOf(song(MusicSource.QQ)) })
        val options = ScrapeOptions(policies = mapOf(MetadataField.TITLE to FieldPolicy()))
        val result = MetadataSourcesClient(netease, qq).metadata(track, options, null)
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
        val result = MetadataSourcesClient(netease, qq).metadata(track.copy(album = null), options, forced)
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
        val result = MetadataSourcesClient(netease, qq).metadata(track.copy(durationMs = 100_000L), options, null)
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
        val result = MetadataSourcesClient(*clients.toTypedArray()).metadata(fileTrack, options, null)
        assertEquals(RemoteValue.Available("title"), result.metadata.title)
        assertEquals(listOf(1, 1), clients.map { it.searches })
    }

    @Test
    fun emptySelectionDoesNotSearchOrDownloadInEitherEntryPoint() = runBlocking {
        val clients = MusicSource.entries.map { source -> StubClient(source, { error("No fields selected") }) }
        val client = MetadataSourcesClient(*clients.toTypedArray())
        val options = ScrapeOptions(policies = emptyMap())
        assertTrue(client.candidates(track, options).ranked.isEmpty())
        val prepared = client.metadata(track, options, null)
        assertEquals(ScrapedMetadata(), prepared.metadata)
        assertEquals(ScrapeKind.UNCHANGED, prepared.disposition.kind)
        assertEquals(listOf(0, 0), clients.map { it.searches })
    }

    private fun order(
        sources: ScrapeSources = ScrapeSources(),
        forced: SongCandidate? = null,
        searchGroup: MetadataGroup = MetadataGroup.TAGS,
    ): (MetadataGroup) -> List<MusicSource> = { orderedSources(it, sources, forced, searchGroup) }

    private fun song(source: MusicSource) = SongCandidate(
        1, "title", listOf("artist"), "album", null, 1000L, null, null, 1, source,
    )

    @Test
    fun firstSourceBatchesAllSelectedGroupsInOneRequest() {
        val selected = setOf(MetadataField.TITLE, MetadataField.LYRICS, MetadataField.COVER)
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = setOf(MetadataField.TITLE),
            selected = selected,
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(selected, fields)
    }

    @Test
    fun unselectedFieldsAreNeverRequested() {
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = MetadataField.entries.toSet(),
            selected = setOf(MetadataField.TITLE),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun alreadyAttemptedFieldsOnThisSourceAreNotRequestedAgain() {
        val fields = metadataRequestFields(
            source = MusicSource.NETEASE,
            required = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE)),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.LYRICS), fields)
    }

    @Test
    fun otherGroupsJoinTheBatchOnlyAfterEarlierSourcesHaveMissedThem() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.TITLE),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.LYRICS)),
            downloaded = mapOf(MusicSource.NETEASE to ScrapedMetadata()),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE, MetadataField.LYRICS), fields)
    }

    @Test
    fun fallbackDoesNotPreemptAGroupEarlierSourcesHaveNotAttempted() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.TITLE),
            selected = setOf(MetadataField.TITLE, MetadataField.LYRICS),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun availableValuesAreNotAddedAsEligibleFallbackFields() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = setOf(MetadataField.ARTISTS),
            selected = setOf(MetadataField.TITLE, MetadataField.ARTISTS),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE, MetadataField.ARTISTS)),
            downloaded = mapOf(
                MusicSource.NETEASE to ScrapedMetadata(
                    title = RemoteValue.Available("t"),
                    artists = RemoteValue.Unavailable,
                ),
            ),
            order = order(),
        )
        assertEquals(setOf(MetadataField.ARTISTS), fields)
    }

    @Test
    fun confirmedAbsentCountsAsAMissForFallbackEligibility() {
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = emptySet(),
            selected = setOf(MetadataField.TITLE),
            attempted = mapOf(MusicSource.NETEASE to setOf(MetadataField.TITLE)),
            downloaded = mapOf(MusicSource.NETEASE to ScrapedMetadata(title = RemoteValue.ConfirmedAbsent)),
            order = order(),
        )
        assertEquals(setOf(MetadataField.TITLE), fields)
    }

    @Test
    fun sourceAbsentFromAGroupDoesNotTakeThatGroupsFieldsAsEligible() {
        val sources = ScrapeSources(tags = sourceSelections(MusicSource.NETEASE), lyrics = sourceSelections(MusicSource.QQ))
        val fields = metadataRequestFields(
            source = MusicSource.QQ,
            required = emptySet(),
            selected = MetadataField.entries.toSet(),
            attempted = emptyMap(),
            downloaded = emptyMap(),
            order = order(sources),
        )
        assertEquals(setOf(MetadataField.LYRICS), fields)
    }

    @Test
    fun forcedCandidateReordersOnlyTheSearchGroup() {
        val forced = song(MusicSource.QQ)
        val sources = ScrapeSources()
        assertEquals(
            listOf(MusicSource.QQ, MusicSource.NETEASE),
            orderedSources(MetadataGroup.TAGS, sources, forced, MetadataGroup.TAGS),
        )
        assertEquals(
            listOf(MusicSource.NETEASE, MusicSource.QQ),
            orderedSources(MetadataGroup.LYRICS, sources, forced, MetadataGroup.TAGS),
        )
    }
}
