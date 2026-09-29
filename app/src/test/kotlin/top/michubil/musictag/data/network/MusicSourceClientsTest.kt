package top.michubil.musictag.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.michubil.musictag.data.model.*
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MusicSourceClientsTest {
    @Test
    fun batchReusesEnrichedSongAlbumAndCoverButNextBatchFetchesAgain() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source)
            val root = client(source, transport)
            val batch = root.forBatch(this)
            val selected = setOf(MetadataField.TITLE, MetadataField.DATE, MetadataField.COVER)
            batch.enrich(candidate(source))
            repeat(2) {
                val result = batch.metadata(candidate(source), selected)
                assertEquals(RemoteValue.Available("Song"), result.title)
                assertTrue(result.date is RemoteValue.Available)
                assertEquals(RemoteValue.Available(transport.image), result.cover)
            }
            assertEquals(2, transport.jsonRequests.size)
            assertEquals(1, transport.coverRequests)
            root.forBatch(this).metadata(candidate(source), selected)
            assertEquals(4, transport.jsonRequests.size)
            assertEquals(2, transport.coverRequests)
        }
    }

    @Test
    fun cachedSearchDoesNotReuseAnotherFilesMatchingDecision() = runBlocking {
        val transport = FixtureTransport(MusicSource.QQ)
        val batch = MetadataSourcesClient(QqMusicClient(transport)).forBatch(this)
        val options = ScrapeOptions(sources = ScrapeSources(sourceSelections(MusicSource.QQ)))
        val local = LocalTrack("Song.mp3", "Song", listOf("Artist"), "Album", 180_000)
        val matched = batch.candidates(local, options)
        val differentAudio = batch.candidates(local.copy(durationMs = 300_000), options)
        assertTrue(matched.outcome is top.michubil.musictag.data.match.MatchOutcome.Accept)
        assertTrue(differentAudio.outcome is top.michubil.musictag.data.match.MatchOutcome.Review)
        // Combined query is cached; the second file still tries its own pure-title fallback.
        assertEquals(2, transport.jsonRequests.size)
    }

    @Test
    fun independentLyricsAndCoverRequestsCanBothStartBeforeEitherFinishes() = runBlocking {
        for (source in MusicSource.entries) {
            withTimeout(5_000) {
                val fixture = FixtureTransport(source)
                val lyricsStarted = CompletableDeferred<Unit>()
                val coverStarted = CompletableDeferred<Unit>()
                val transport = object : MusicTransport by fixture {
                    override suspend fun json(url: String, referer: String, label: String, body: ByteArray?,
                        contentType: String): JSONObject {
                        lyricsStarted.complete(Unit)
                        coverStarted.await()
                        return fixture.json(url, referer, label, body, contentType)
                    }

                    override suspend fun cover(rawUrl: String, referer: String,
                        trustedHost: (String) -> Boolean): CoverImage {
                        coverStarted.complete(Unit)
                        lyricsStarted.await()
                        return fixture.cover(rawUrl, referer, trustedHost)
                    }
                }
                val result = client(source, transport).forBatch(this)
                    .metadata(candidate(source), setOf(MetadataField.LYRICS, MetadataField.COVER))
                assertTrue(result.lyrics is RemoteValue.Available)
                assertTrue(result.cover is RemoteValue.Available)
            }
        }
    }

    @Test
    fun detailFailurePreservesLyricsAndKnownCover() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, detailFailure = IOException("detail unavailable"))
            val result = client(source, transport).metadata(candidate(source), fields)
            assertEquals(RemoteValue.Unavailable, result.title)
            assertEquals(RemoteValue.Available("[00:01.000]Hello"), result.lyrics)
            assertEquals(RemoteValue.Available(transport.image), result.cover)
            assertEquals(2, transport.jsonRequests.size)
            assertEquals(1, transport.coverRequests)
        }
    }

    @Test
    fun wrongSongDetailCannotSupplyTagsButDoesNotBlockIndependentContent() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, detailId = 999)
            val result = client(source, transport).metadata(candidate(source), fields)
            assertEquals(RemoteValue.Unavailable, result.title)
            assertTrue(result.lyrics is RemoteValue.Available)
            assertTrue(result.cover is RemoteValue.Available)
        }
    }

    @Test
    fun lyricAndCoverFailuresPreserveSuccessfulTags() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, lyricFailure = IOException("lyrics unavailable"),
                coverFailure = IOException("cover unavailable"))
            val result = client(source, transport).metadata(candidate(source), fields)
            assertEquals(RemoteValue.Available("Song"), result.title)
            assertEquals(RemoteValue.Unavailable, result.lyrics)
            assertEquals(RemoteValue.Unavailable, result.cover)
        }
    }

    @Test
    fun knownCoverAndLyricsDoNotRequireSongOrAlbumDetails() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, detailFailure = AssertionError("Unexpected detail request"))
            val result = client(source, transport).metadata(candidate(source), fields - MetadataField.TITLE)
            assertTrue(result.lyrics is RemoteValue.Available)
            assertTrue(result.cover is RemoteValue.Available)
            assertEquals(1, transport.jsonRequests.size)
            assertEquals(1, transport.coverRequests)
        }
    }

    @Test
    fun emptySelectionDoesNotRequestAnything() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source)
            assertEquals(ScrapedMetadata(), client(source, transport).metadata(candidate(source), emptySet()))
            assertTrue(transport.jsonRequests.isEmpty())
            assertEquals(0, transport.coverRequests)
        }
    }

    @Test
    fun cancellationAtEachRequestStageIsRethrown() {
        for (source in MusicSource.entries) {
            for (stage in listOf("detail", "lyrics", "cover")) {
                val cancellation = CancellationException(stage)
                val transport = FixtureTransport(source,
                    detailFailure = cancellation.takeIf { stage == "detail" },
                    lyricFailure = cancellation.takeIf { stage == "lyrics" },
                    coverFailure = cancellation.takeIf { stage == "cover" })
                val caught = assertThrows(CancellationException::class.java) {
                    runBlocking { client(source, transport).metadata(candidate(source), fields) }
                }
                assertEquals(stage, caught.message)
            }
        }
    }

    @Test
    fun invalidStatusDoesNotTurnLyricsIntoSuccessOrConfirmedAbsence() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, lyricCode = "invalid")
            val result = client(source, transport).metadata(candidate(source), setOf(MetadataField.LYRICS))
            assertEquals(RemoteValue.Unavailable, result.lyrics)
        }
    }

    @Test
    fun emptyLyricsRemainUnavailableWithoutPlatformEvidenceOfAbsence() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, lyricText = "")
            val result = client(source, transport).metadata(candidate(source), setOf(MetadataField.LYRICS))
            assertEquals(RemoteValue.Unavailable, result.lyrics)
        }
    }

    @Test
    fun qqExplicitInstrumentalNoticeConfirmsAbsentLyrics() = runBlocking {
        val transport = FixtureTransport(MusicSource.QQ,
            lyricText = "[00:00.000]此歌曲为没有填词的纯音乐，请您欣赏")

        val result = QqMusicClient(transport).metadata(candidate(MusicSource.QQ), setOf(MetadataField.LYRICS))

        assertEquals(RemoteValue.ConfirmedAbsent, result.lyrics)
    }

    @Test
    fun missingTitleDoesNotDiscardLyricsOrCover() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source, detailTitle = JSONObject.NULL)
            val result = client(source, transport).metadata(candidate(source), fields)
            assertEquals(RemoteValue.Unavailable, result.title)
            assertTrue(result.lyrics is RemoteValue.Available)
            assertTrue(result.cover is RemoteValue.Available)
        }
    }

    @Test
    fun searchReturnsCandidatesWithPlatformDurationUnits() = runBlocking {
        for (source in MusicSource.entries) {
            val transport = FixtureTransport(source)
            val song = client(source, transport).search("Song").candidates.single()
            assertEquals(source, song.source)
            assertEquals(1L, song.id)
            assertEquals("Song", song.title)
            assertEquals(180000L, song.durationMs)
            assertEquals(1, transport.jsonRequests.size)
        }
    }

    @Test
    fun qqSearchDoesNotHideMalformedCandidate() {
        val transport = FixtureTransport(MusicSource.QQ, detailTitle = JSONObject.NULL)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { QqMusicClient(transport).search("Song") }
        }
    }

    @Test
    fun qqFullSearchTreatsAnEmptySongListAsNoCandidates() = runBlocking {
        val transport = FixtureTransport(MusicSource.QQ, searchIds = emptyList())
        assertTrue(QqMusicClient(transport).search("Song").candidates.isEmpty())
        assertEquals(1, transport.jsonRequests.size)
    }

    @Test
    fun qqFullSearchRemovesHighlightMarkupFromCandidateTitle() = runBlocking {
        val transport = FixtureTransport(MusicSource.QQ, detailTitle = "<em>Song</em>")
        assertEquals("Song", QqMusicClient(transport).search("Song").candidates.single().title)
    }

    @Test
    fun qqSearchKeepsEveryDistinctCandidateFromFullSongSearch() = runBlocking {
        val transport = FixtureTransport(MusicSource.QQ, searchIds = listOf(2, 1, 2))
        val songs = QqMusicClient(transport).search("Song").candidates
        assertEquals(listOf(2L, 1L), songs.map { it.id })
        assertTrue(songs.all { it.source == MusicSource.QQ && it.durationMs == 180000L })
        assertEquals(1, transport.jsonRequests.size)
        val request = requireNotNull(transport.searchRequest)
        assertEquals("Song", request.getString("query"))
        assertEquals(15, request.getInt("num_per_page"))
        assertEquals(1, request.getInt("page_num"))
    }

    private fun client(source: MusicSource, transport: MusicTransport): MusicSourceClient = when (source) {
        MusicSource.NETEASE -> NetEaseClient(transport)
        MusicSource.QQ -> QqMusicClient(transport)
    }

    private fun candidate(source: MusicSource) = SongCandidate(
        id = 1, title = "Search title", artists = listOf("Artist"), album = "Album", albumId = 2,
        durationMs = 1000, coverUrl = if (source == MusicSource.QQ) "https://y.gtimg.cn/cover.jpg"
            else "https://p1.music.126.net/cover.jpg",
        publishTimeMs = null, trackNumber = null, source = source, mid = "songMid",
    )

    private val fields = setOf(MetadataField.TITLE, MetadataField.LYRICS, MetadataField.COVER)

    private class FixtureTransport(
        val source: MusicSource,
        val detailFailure: Throwable? = null,
        val lyricFailure: Throwable? = null,
        val coverFailure: Throwable? = null,
        val detailId: Long? = null,
        val searchIds: List<Long> = listOf(1, 1),
        val detailTitle: Any = "Song",
        val lyricCode: Any = if (source == MusicSource.NETEASE) 200 else 0,
        val lyricText: String = "[00:01.000]Hello",
    ) : MusicTransport {
        val jsonRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var searchRequest: JSONObject? = null
        var coverRequests = 0
        val image = CoverImage(byteArrayOf(1), "image/jpeg", 1, 1)

        override suspend fun json(url: String, referer: String, label: String, body: ByteArray?,
            contentType: String): JSONObject {
            jsonRequests += url
            if ("cloudsearch" in url) {
                return JSONObject("""{"code":200,"result":{"songs":[{"id":1,"name":" Song ","dt":180000}]}}""")
            }
            if ("lyric" in url) {
                lyricFailure?.let { throw it }
                return if (source == MusicSource.NETEASE) {
                    JSONObject().put("code", lyricCode).put("lrc", JSONObject().put("lyric", lyricText))
                } else {
                    JSONObject().put("code", lyricCode)
                        .put("lyric", Base64.getEncoder().encodeToString(lyricText.toByteArray()))
                }
            }
            if ("/weapi/v1/album/" in url) {
                return JSONObject().put("code", 200).put("album", JSONObject()
                    .put("id", 2).put("publishTime", 1_700_000_000_000L))
            }
            check(url.endsWith("/weapi/v3/song/detail") || url.endsWith("/cgi-bin/musicu.fcg")) {
                "Unexpected request: $url"
            }
            if (source == MusicSource.QQ && body != null) {
                val payload = JSONObject(body.toString(Charsets.UTF_8))
                if (payload.has("music.musichallAlbum.AlbumInfoServer")) {
                    return JSONObject().put("code", 0).put("music.musichallAlbum.AlbumInfoServer",
                        JSONObject().put("code", 0).put("data", JSONObject().put("basicInfo",
                            JSONObject().put("publishDate", "2023-11-14"))))
                }
                val request = payload.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicDesktop")
                if (request != null) {
                    check(request.string("module") == "music.search.SearchCgiService")
                    check(request.string("method") == "DoSearchForQQMusicDesktop")
                    check(payload.optJSONObject("comm")?.long("ct") == 6L)
                    searchRequest = request.getJSONObject("param")
                    val items = org.json.JSONArray()
                    searchIds.forEach { id ->
                        items.put(JSONObject().put("id", id).put("title", detailTitle).put("interval", 180)
                            .put("singer", org.json.JSONArray().put(JSONObject().put("name", "Artist")))
                            .put("album", JSONObject().put("id", 2).put("title", "Album")))
                    }
                    return JSONObject().put("code", 0)
                        .put("music.search.SearchCgiService.DoSearchForQQMusicDesktop", JSONObject().put("code", 0)
                            .put("data", JSONObject().put("body", JSONObject().put("song", JSONObject().put("list", items)))))
                }
            }
            detailFailure?.let { throw it }
            val requestedId = if (source == MusicSource.QQ) {
                JSONObject(requireNotNull(body).toString(Charsets.UTF_8))
                    .getJSONObject("music.pf_song_detail_svr").getJSONObject("param").getLong("song_id")
            } else 1L
            val song = JSONObject().put("id", detailId ?: requestedId).put("name", detailTitle)
                .put("title", detailTitle).put("dt", 180000).put("interval", 180)
            return if (source == MusicSource.NETEASE) {
                JSONObject().put("code", 200).put("songs", org.json.JSONArray().put(song))
            } else {
                JSONObject().put("code", 0).put("music.pf_song_detail_svr",
                    JSONObject().put("code", 0).put("data", JSONObject().put("track_info", song)))
            }
        }

        override suspend fun cover(rawUrl: String, referer: String, trustedHost: (String) -> Boolean): CoverImage {
            coverRequests++
            check(trustedHost(URI(rawUrl).host))
            check(!trustedHost("untrusted.example"))
            coverFailure?.let { throw it }
            return image
        }
    }
}
