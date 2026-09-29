package top.michubil.musictag.data.network

import top.michubil.musictag.data.operationResult

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.ReleaseDate
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.TrackIndex
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MusicSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

class NetEaseClient internal constructor(
    private val transport: MusicTransport,
    private val requests: SourceRequests? = null,
) : MusicSourceClient {
    constructor() : this(MusicHttp)

    override val source = MusicSource.NETEASE

    override fun forBatch(scope: CoroutineScope): MusicSourceClient = NetEaseClient(transport, SourceRequests(scope))

    override suspend fun search(query: String, page: Int): SearchPage = requests?.searches.load(query to page) {
        val offset = page * PAGE_SIZE
        val payload = JSONObject()
            .put("s", query)
            .put("type", 1)
            .put("limit", PAGE_SIZE)
            .put("offset", offset)
            .put("total", true)
        val root = post("/weapi/cloudsearch/pc", payload)
        val result = root.optJSONObject("result") ?: error("网易云搜索响应结构无效")
        val songs = result.optJSONArray("songs")
        if (songs == null) {
            check(result.long("songCount") == 0L) { "网易云没有返回搜索结果" }
            return@load SearchPage(emptyList())
        }
        val candidates = songs.songCandidates(source, ::candidateFromSong)
        val total = result.long("songCount")
        val nextPage = total?.takeIf { it > offset + songs.length() }?.let { page + 1 }
        SearchPage(candidates, nextPage)
    }

    override suspend fun enrich(candidate: SongCandidate): SongCandidate {
        require(candidate.source == source && candidate.id > 0)
        return requireNotNull(candidateFromSong(fetchSongDetail(candidate.id)))
    }

    private suspend fun fetchSongDetail(id: Long): JSONObject = requests?.songs.load(id) {
        val payload = JSONObject().put("c", "[{\"id\":$id}]").put("ids", "[$id]")
        val song = post("/weapi/v3/song/detail", payload).optJSONArray("songs").objects().firstOrNull()
            ?: error("网易云没有返回歌曲详情")
        val detailed = candidateFromSong(song) ?: error("网易云歌曲详情结构无效")
        check(detailed.id == id) { "网易云返回了不一致的歌曲详情" }
        song
    }

    override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata = coroutineScope {
        require(candidate.source == source && candidate.id > 0)
        if (fields.isEmpty()) return@coroutineScope ScrapedMetadata()
        val songId = candidate.id
        val lyrics = async {
            if (MetadataField.LYRICS in fields) downloadLyrics(songId) else RemoteValue.Unavailable
        }
        val needsDetail = fields.any { it in MetadataField.tagFields } ||
            (MetadataField.COVER in fields && candidate.coverUrl == null)
        val detail = if (needsDetail) operationResult {
            val song = fetchSongDetail(songId)
            val base = requireNotNull(candidateFromSong(song))
            song to base
        }.getOrNull() else null
        val song = detail?.first
        val base = detail?.second
        val album = (base?.albumId ?: candidate.albumId)?.takeIf {
            fields.any { it in setOf(MetadataField.DATE, MetadataField.TRACK, MetadataField.DISC) } ||
                (MetadataField.COVER in fields && (base?.coverUrl ?: candidate.coverUrl) == null)
        }?.let { albumId ->
            operationResult { requests?.albums.load(albumId) {
                post("/weapi/v1/album/$albumId", JSONObject().put("id", albumId)).also {
                    check(it.optJSONObject("album")?.long("id") == albumId) { "网易云返回了不一致的专辑详情" }
                }
            } }.getOrNull()
        }
        val albumInfo = album?.optJSONObject("album")
        val albumSongs = album?.optJSONArray("songs").objects()
        val albumTrack = albumSongs.indexOfFirst { it.long("id") == songId }.takeIf { it >= 0 }?.plus(1)
        val trackNumber = base?.trackNumber ?: albumTrack
        val trackTotal = albumSongs.size.takeIf { it > 0 }
        val discNumber = parseDisc(song?.string("cd"))
        val discTotal = albumSongs.mapNotNull { parseDisc(it.string("cd")) }.maxOrNull()

        val coverUrl = albumInfo?.string("picUrl") ?: base?.coverUrl ?: candidate.coverUrl
        val publishTime = albumInfo?.long("publishTime")?.takeIf { it > 0 } ?: base?.publishTimeMs

        val cover = coverUrl?.takeIf { MetadataField.COVER in fields }?.let { url ->
            operationResult { requests?.covers.load(url) { downloadCover(url) } }.getOrNull()
                ?.let { RemoteValue.Available(it) }
        } ?: RemoteValue.Unavailable
        ScrapedMetadata(
            title = base?.title?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            artists = base?.artists?.takeIf(List<String>::isNotEmpty)?.let { RemoteValue.Available(it) }
                ?: RemoteValue.Unavailable,
            album = base?.album?.takeIf(String::isNotBlank)?.let { RemoteValue.Available(it) }
                ?: RemoteValue.Unavailable,
            date = publishTime?.let { operationResult { releaseDate(it) }.getOrNull() }
                ?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            track = trackNumber?.takeIf { it > 0 }
                ?.let { RemoteValue.Available(TrackIndex(it, trackTotal)) } ?: RemoteValue.Unavailable,
            disc = discNumber?.takeIf { it > 0 }
                ?.let { RemoteValue.Available(TrackIndex(it, discTotal)) } ?: RemoteValue.Unavailable,
            lyrics = lyrics.await(),
            cover = cover,
        )
    }

    private suspend fun downloadLyrics(songId: Long): RemoteValue<String> {
        val lyric = operationResult {
            post("/weapi/song/lyric", JSONObject().put("id", songId).put("lv", -1).put("tv", -1))
        }.getOrNull() ?: return RemoteValue.Unavailable
        if (lyric.boolean("pureMusic") == true) return RemoteValue.ConfirmedAbsent
        val merged = LyricsCodec.merge(lyric.optJSONObject("lrc")?.string("lyric"), lyric.optJSONObject("tlyric")?.string("lyric"))
        return when {
            merged != null -> RemoteValue.Available(merged)
            lyric.boolean("nolyric") == true -> RemoteValue.ConfirmedAbsent
            else -> RemoteValue.Unavailable
        }
    }

    private suspend fun post(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val encrypted = WeApiCrypto.encrypt(payload.toString())
        val body = "params=${encoded(encrypted.params)}&encSecKey=${encoded(encrypted.encSecKey)}"
            .toByteArray(StandardCharsets.UTF_8)
        transport.json("https://music.163.com$path", REFERER, "网易云", body,
            contentType = "application/x-www-form-urlencoded").also {
            check(it.long("code") == 200L) { "网易云接口暂不可用，请稍后重试" }
        }
    }

    private suspend fun downloadCover(rawUrl: String): CoverImage = transport.cover(rawUrl, REFERER) {
        it == "music.126.net" || it.endsWith(".music.126.net")
    }

    private fun candidateFromSong(song: JSONObject): SongCandidate? {
        val id = song.long("id")?.takeIf { it > 0 } ?: return null
        val album = song.optJSONObject("al") ?: song.optJSONObject("album")
        val artistArray = song.optJSONArray("ar") ?: song.optJSONArray("artists")
        return SongCandidate(
            id = id,
            title = song.string("name") ?: return null,
            artists = artistArray.objects().mapNotNull { it.string("name") },
            album = album?.string("name").orEmpty(),
            albumId = album?.long("id")?.takeIf { it > 0 },
            durationMs = (song.long("dt") ?: song.long("duration"))?.takeIf { it > 0 },
            coverUrl = album?.string("picUrl"),
            publishTimeMs = song.long("publishTime")?.takeIf { it > 0 },
            trackNumber = (song.long("no") ?: song.long("position"))?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
            source = source,
        )
    }

    private fun releaseDate(timestamp: Long): ReleaseDate {
        val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        return ReleaseDate(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun parseDisc(value: String?): Int? = value?.trim()?.substringBefore('/')?.toIntOrNull()

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val REFERER = "https://music.163.com/"
        const val PAGE_SIZE = 10
    }
}
