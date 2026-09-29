package top.michubil.musictag.data.network

import top.michubil.musictag.data.operationResult

import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.model.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Anonymous web requests. */
class QqMusicClient private constructor(
    private val transport: MusicTransport,
    private val requests: SourceRequests?,
    private val limiter: RequestLimiter,
) : MusicSourceClient {
    internal constructor(transport: MusicTransport) : this(transport, null, RequestLimiter())
    constructor() : this(MusicHttp)

    override val source = MusicSource.QQ
    override val supportedFields = MetadataField.entries.toSet() - MetadataField.DISC

    override fun forBatch(scope: CoroutineScope): MusicSourceClient = QqMusicClient(transport, SourceRequests(scope), limiter)

    override suspend fun search(query: String, page: Int): SearchPage = requests?.searches.load(query to page) {
        val module = "music.search.SearchCgiService"
        val method = "DoSearchForQQMusicDesktop"
        val pageNum = page + 1
        val params = JSONObject().put("query", query).put("num_per_page", PAGE_SIZE).put("page_num", pageNum)
            .put("remoteplace", "txt.mac.search").put("search_type", 0).put("grp", 1)
            .put("searchid", UUID.randomUUID().toString()).put("nqc_flag", 0)
        val data = cgi(module, method, params, "$module.$method",
            JSONObject().put("ct", 6).put("cv", 80600).put("tmeAppID", "qqmusic"))
        val songObject = data.optJSONObject("body")?.optJSONObject("song") ?: error("QQ 音乐搜索响应结构无效")
        val songs = songObject.optJSONArray("list") ?: error("QQ 音乐搜索响应结构无效")
        val candidates = songs.songCandidates(source, ::candidateFromSong)
        val total = songObject.long("totalnum")
        val nextPage = total?.takeIf { it > pageNum.toLong() * PAGE_SIZE }?.let { page + 1 }
        SearchPage(candidates, nextPage)
    }

    override suspend fun enrich(candidate: SongCandidate): SongCandidate {
        require(candidate.source == source && candidate.id > 0)
        return requireNotNull(candidateFromSong(fetchSongDetail(candidate.id)))
    }

    override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata = coroutineScope {
        require(candidate.source == source && candidate.id > 0)
        if (fields.isEmpty()) return@coroutineScope ScrapedMetadata()
        val needsDetail = fields.any { it in MetadataField.tagFields } ||
            (MetadataField.COVER in fields && candidate.coverUrl == null) ||
            (MetadataField.LYRICS in fields && candidate.mid == null)
        val song = if (needsDetail) operationResult { fetchSongDetail(candidate.id) }.getOrNull() else null
        val base = song?.let(::candidateFromSong)
        val lyricsRequest = async {
            if (MetadataField.LYRICS in fields) {
                val mid = base?.mid ?: candidate.mid
                if (mid == null) RemoteValue.Unavailable else operationResult { lyrics(mid) }.getOrElse { RemoteValue.Unavailable }
            } else RemoteValue.Unavailable
        }
        val coverRequest = async {
            if (MetadataField.COVER in fields) {
                val url = base?.coverUrl ?: candidate.coverUrl
                if (url == null) RemoteValue.Unavailable else operationResult {
                    RemoteValue.Available(requests?.covers.load(url) {
                        // Keep album artwork; a missing album cover must not turn into a singer portrait.
                        limiter.run { transport.cover(url, REFERER) { it == "y.gtimg.cn" } }
                    })
                }.getOrElse { RemoteValue.Unavailable }
            } else RemoteValue.Unavailable
        }
        val album = song?.optJSONObject("album")
        val albumDetail = if (MetadataField.DATE in fields && album?.string("time_public") == null) {
            (base?.albumId ?: candidate.albumId)?.let { id -> operationResult {
                requests?.albums.load(id) {
                    cgi("music.musichallAlbum.AlbumInfoServer", "GetAlbumDetail", JSONObject().put("albumId", id)).also {
                        check(it.optJSONObject("basicInfo") != null) { "QQ 音乐专辑详情结构无效" }
                    }
                }
            }.getOrNull() }
        } else null
        val albumInfo = albumDetail?.optJSONObject("basicInfo")
        val date = sequenceOf(albumInfo?.string("publishDate"), albumInfo?.string("time_public"),
            album?.string("time_public"), song?.string("time_public"))
            .filterNotNull().mapNotNull(ReleaseDate::parseOrNull).firstOrNull()
        ScrapedMetadata(
            title = base?.title?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            artists = base?.artists?.takeIf { it.isNotEmpty() }?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            album = base?.album?.takeIf(String::isNotBlank)?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            date = date?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            track = base?.trackNumber?.let { RemoteValue.Available(TrackIndex(it, null)) } ?: RemoteValue.Unavailable,
            // index_cd is an index, not a documented one-based disc number. Do not invent a disc/total.
            disc = RemoteValue.Unavailable,
            lyrics = lyricsRequest.await(),
            cover = coverRequest.await(),
        )
    }

    private suspend fun fetchSongDetail(id: Long): JSONObject = requests?.songs.load(id) {
        val data = cgi("music.pf_song_detail_svr", "get_song_detail_yqq", JSONObject().put("song_id", id))
        val song = data.optJSONObject("track_info") ?: error("QQ 音乐歌曲详情结构无效")
        check(song.long("id") == id) { "QQ 音乐返回了不一致的歌曲详情" }
        check(candidateFromSong(song) != null) { "QQ 音乐歌曲详情结构无效" }
        song
    }

    private suspend fun lyrics(mid: String): RemoteValue<String> {
        require(MID.matches(mid)) { "QQ 音乐歌曲标识无效" }
        val query = mapOf("songmid" to mid, "format" to "json", "nobase64" to "0", "g_tk" to "5381",
            "loginUin" to "0", "hostUin" to "0", "inCharset" to "utf8", "outCharset" to "utf-8",
            "notice" to "0", "platform" to "yqq", "needNewCode" to "0")
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val root = limiter.run { transport.json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?$query", REFERER, source.label) }
        check(root.long("code") == 0L && (!root.has("retcode") || root.long("retcode") == 0L)) {
            "QQ 音乐歌词接口暂不可用"
        }
        check(root.has("lyric") && !root.isNull("lyric")) { "QQ 音乐未返回歌词内容" }
        fun decode(value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
            unescape(String(Base64.getDecoder().decode(it.filterNot(Char::isWhitespace)), Charsets.UTF_8))
        }
        val original = decode(root.string("lyric"))
        val translation = operationResult { decode(root.string("trans")) }.getOrNull()
        val merged = LyricsCodec.merge(original, translation)
        // Empty responses are not reliable evidence that a track has no lyrics.
        if (merged == null) return RemoteValue.Unavailable
        val lines = LyricsCodec.parse(merged).flatMap { it.lines }.map(String::trim)
        if (lines.isEmpty()) return RemoteValue.Unavailable
        if (lines.all { it in setOf("此歌曲为没有填词的纯音乐，请您欣赏", "纯音乐，请欣赏") }) {
            return RemoteValue.ConfirmedAbsent
        }
        return RemoteValue.Available(merged)
    }

    private suspend fun cgi(module: String, method: String, params: JSONObject,
        requestKey: String = module,
        comm: JSONObject = JSONObject().put("ct", 24).put("cv", 0).put("uin", 0)
            .put("format", "json").put("g_tk", 5381).put("platform", "yqq.json"),
    ): JSONObject {
        val payload = JSONObject().put(requestKey, JSONObject().put("module", module).put("method", method).put("param", params))
            .put("comm", comm)
        val root = limiter.run { transport.json("https://u.y.qq.com/cgi-bin/musicu.fcg", REFERER, source.label,
            payload.toString().toByteArray(Charsets.UTF_8)) }
        check(!root.has("code") || root.long("code") == 0L) { "QQ 音乐接口暂不可用" }
        val response = root.optJSONObject(requestKey) ?: error("QQ 音乐接口响应结构无效")
        check(response.long("code") == 0L) { "QQ 音乐接口暂不可用，请稍后重试" }
        return response.optJSONObject("data") ?: error("QQ 音乐没有返回数据")
    }

    private class RequestLimiter {
        private val connections = Semaphore(2)
        private val requestStart = Mutex()
        private val requestGap = 300.milliseconds
        private var nextAllowed = TimeSource.Monotonic.markNow()

        suspend fun <T> run(block: suspend () -> T): T = connections.withPermit {
            requestStart.withLock {
                val wait = nextAllowed - TimeSource.Monotonic.markNow()
                if (wait.isPositive()) delay(wait)
                nextAllowed = TimeSource.Monotonic.markNow() + requestGap
            }
            block()
        }
    }

    private fun candidateFromSong(song: JSONObject): SongCandidate? {
        val id = song.long("id")?.takeIf { it > 0 } ?: return null
        val title = song.string("title") ?: song.string("name") ?: return null
        val album = song.optJSONObject("album")
        val albumMid = album?.string("mid")?.takeIf(MID::matches) ?: album?.string("pmid")?.takeIf(MID::matches)
        return SongCandidate(
            id = id,
            title = plainText(title),
            artists = song.optJSONArray("singer").objects().mapNotNull { it.string("name")?.let(::plainText) },
            album = (album?.string("title") ?: album?.string("name"))?.let(::plainText).orEmpty(),
            albumId = album?.long("id")?.takeIf { it > 0 },
            durationMs = song.long("interval")?.takeIf { it in 1..86_400 }?.times(1000),
            coverUrl = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" },
            publishTimeMs = null,
            trackNumber = song.long("index_album")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
            source = source,
            mid = song.string("mid")?.takeIf(MID::matches),
        )
    }

    private fun plainText(value: String): String = unescape(value).replace(EM_TAG, "")

    private fun unescape(value: String): String = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(value) { match ->
        val code = match.groupValues[1].let { if (it.startsWith('x')) it.drop(1).toIntOrNull(16) else it.toIntOrNull() }
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
    }.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private companion object {
        const val REFERER = "https://y.qq.com/"
        const val PAGE_SIZE = 15
        val MID = Regex("[A-Za-z0-9]+")
        val EM_TAG = Regex("</?em>")
    }
}
