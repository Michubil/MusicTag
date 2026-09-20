package top.michubil.musictag.data.network

import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Anonymous web requests. */
class QqMusicClient internal constructor(private val transport: MusicTransport) : MusicSourceClient {
    constructor() : this(MusicHttp)

    override val source = MusicSource.QQ
    override val supportedFields = MetadataField.entries.toSet() - MetadataField.DISC
    private val connections = Semaphore(2)
    private val requestStart = Mutex()
    private val requestGap = 300.milliseconds
    private var nextAllowed = TimeSource.Monotonic.markNow()

    override suspend fun search(query: String): List<SongCandidate> {
        val keyword = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val root = limited {
            transport.json("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&key=$keyword",
                REFERER, source.label)
        }
        check(root.long("code") == 0L && (!root.has("subcode") || root.long("subcode") == 0L)) {
            "QQ 音乐搜索接口暂不可用"
        }
        val songs = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist")
            ?: error("QQ 音乐搜索响应结构无效")
        val ids = List(songs.length()) { index ->
            songs.optJSONObject(index)?.long("id")?.takeIf { it > 0 }
                ?: error("QQ 音乐歌曲标识无效")
        }.distinct()
        // Quick search lacks album and duration. Enrich every candidate before ranking;
        // a failed detail request must not hide an alternative recording from the matcher.
        return ids.map { id ->
            candidateFromSong(fetchSongDetail(id)) ?: error("QQ 音乐歌曲详情结构无效")
        }
    }

    override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata {
        require(candidate.source == source && candidate.id > 0)
        if (fields.isEmpty()) return ScrapedMetadata()
        val needsDetail = fields.any { it in MetadataGroup.TAGS.fields } ||
            (MetadataField.COVER in fields && candidate.coverUrl == null) ||
            (MetadataField.LYRICS in fields && candidate.mid == null)
        val song = if (needsDetail) sourceResult { fetchSongDetail(candidate.id) }.getOrNull() else null
        val base = song?.let(::candidateFromSong)
        val album = song?.optJSONObject("album")
        val albumDetail = if (MetadataField.DATE in fields && album?.string("time_public") == null) {
            (base?.albumId ?: candidate.albumId)?.let { id -> sourceResult {
                cgi("music.musichallAlbum.AlbumInfoServer", "GetAlbumDetail", JSONObject().put("albumId", id))
            }.getOrNull() }
        } else null
        val albumInfo = albumDetail?.optJSONObject("basicInfo")
        val date = sequenceOf(albumInfo?.string("publishDate"), albumInfo?.string("time_public"),
            album?.string("time_public"), song?.string("time_public"))
            .filterNotNull().mapNotNull(ReleaseDate::parseOrNull).firstOrNull()
        val lyrics = if (MetadataField.LYRICS in fields) {
            val mid = base?.mid ?: candidate.mid
            if (mid == null) RemoteValue.Unavailable else sourceResult { lyrics(mid) }.getOrElse { RemoteValue.Unavailable }
        } else RemoteValue.Unavailable
        val cover = if (MetadataField.COVER in fields) {
            val url = base?.coverUrl ?: candidate.coverUrl
            if (url == null) RemoteValue.Unavailable else sourceResult {
                // Keep album artwork; a missing album cover must not turn into a singer portrait.
                RemoteValue.Available(limited { transport.cover(url, REFERER) { it == "y.gtimg.cn" } })
            }.getOrElse { RemoteValue.Unavailable }
        } else RemoteValue.Unavailable
        return ScrapedMetadata(
            title = base?.title?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            artists = base?.artists?.takeIf { it.isNotEmpty() }?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            album = base?.album?.takeIf(String::isNotBlank)?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            date = date?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            track = base?.trackNumber?.let { RemoteValue.Available(TrackIndex(it, null)) } ?: RemoteValue.Unavailable,
            // index_cd is an index, not a documented one-based disc number. Do not invent a disc/total.
            disc = RemoteValue.Unavailable,
            lyrics = lyrics,
            cover = cover,
        )
    }

    private suspend fun fetchSongDetail(id: Long): JSONObject {
        val data = cgi("music.pf_song_detail_svr", "get_song_detail_yqq", JSONObject().put("song_id", id))
        val song = data.optJSONObject("track_info") ?: error("QQ 音乐歌曲详情结构无效")
        check(song.long("id") == id) { "QQ 音乐返回了不一致的歌曲详情" }
        return song
    }

    private suspend fun lyrics(mid: String): RemoteValue<String> {
        require(MID.matches(mid)) { "QQ 音乐歌曲标识无效" }
        val query = mapOf("songmid" to mid, "format" to "json", "nobase64" to "0", "g_tk" to "5381",
            "loginUin" to "0", "hostUin" to "0", "inCharset" to "utf8", "outCharset" to "utf-8",
            "notice" to "0", "platform" to "yqq", "needNewCode" to "0")
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val root = limited { transport.json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?$query", REFERER, source.label) }
        check(root.long("code") == 0L && (!root.has("retcode") || root.long("retcode") == 0L)) {
            "QQ 音乐歌词接口暂不可用"
        }
        check(root.has("lyric") && !root.isNull("lyric")) { "QQ 音乐未返回歌词内容" }
        fun decode(value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
            unescape(String(Base64.getDecoder().decode(it.filterNot(Char::isWhitespace)), Charsets.UTF_8))
        }
        val original = decode(root.string("lyric"))
        val translation = sourceResult { decode(root.string("trans")) }.getOrNull()
        val merged = LyricsCodec.merge(original, translation)
        // Empty responses are not reliable evidence that a track has no lyrics.
        if (merged == null) return RemoteValue.Unavailable
        val lines = LyricsCodec.parse(merged).flatMap { it.lines }.map(String::trim)
        if (lines.isEmpty() || lines.all { it in setOf("此歌曲为没有填词的纯音乐，请您欣赏", "纯音乐，请欣赏") }) {
            return RemoteValue.Unavailable
        }
        return RemoteValue.Available(merged)
    }

    private suspend fun cgi(module: String, method: String, params: JSONObject): JSONObject {
        val payload = JSONObject().put(module, JSONObject().put("module", module).put("method", method).put("param", params))
            .put("comm", JSONObject().put("ct", 24).put("cv", 0).put("uin", 0)
                .put("format", "json").put("g_tk", 5381).put("platform", "yqq.json"))
        val root = limited { transport.json("https://u.y.qq.com/cgi-bin/musicu.fcg", REFERER, source.label,
            payload.toString().toByteArray(Charsets.UTF_8)) }
        check(!root.has("code") || root.long("code") == 0L) { "QQ 音乐接口暂不可用" }
        val response = root.optJSONObject(module) ?: error("QQ 音乐接口响应结构无效")
        check(response.long("code") == 0L) { "QQ 音乐接口暂不可用，请稍后重试" }
        return response.optJSONObject("data") ?: error("QQ 音乐没有返回数据")
    }

    private suspend fun <T> limited(block: suspend () -> T): T = connections.withPermit {
        requestStart.withLock {
            val wait = nextAllowed - TimeSource.Monotonic.markNow()
            if (wait.isPositive()) delay(wait)
            nextAllowed = TimeSource.Monotonic.markNow() + requestGap
        }
        block()
    }

    private fun candidateFromSong(song: JSONObject): SongCandidate? {
        val id = song.long("id")?.takeIf { it > 0 } ?: return null
        val title = song.string("title") ?: song.string("name") ?: return null
        val album = song.optJSONObject("album")
        val albumMid = album?.string("mid")?.takeIf(MID::matches) ?: album?.string("pmid")?.takeIf(MID::matches)
        return SongCandidate(
            id = id,
            title = unescape(title).replace(Regex("</?em>"), ""),
            artists = song.optJSONArray("singer").objects().mapNotNull { it.string("name")?.let(::unescape) },
            album = album?.string("title")?.let(::unescape) ?: album?.string("name")?.let(::unescape).orEmpty(),
            albumId = album?.long("id")?.takeIf { it > 0 },
            durationMs = song.long("interval")?.takeIf { it in 1..86_400 }?.times(1000),
            coverUrl = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" },
            publishTimeMs = null,
            trackNumber = song.long("index_album")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
            source = source,
            mid = song.string("mid")?.takeIf(MID::matches),
        )
    }

    private fun unescape(value: String): String = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(value) { match ->
        val code = match.groupValues[1].let { if (it.startsWith('x')) it.drop(1).toIntOrNull(16) else it.toIntOrNull() }
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
    }.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private companion object {
        const val REFERER = "https://y.qq.com/"
        val MID = Regex("[A-Za-z0-9]+")
    }
}
