package top.michubil.musictag.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

class NetEaseClient : MusicSourceClient {
    override val source = MusicSource.NETEASE

    override suspend fun search(query: String): List<SongCandidate> {
        val payload = JSONObject()
            .put("s", query)
            .put("type", 1)
            .put("limit", 10)
            .put("offset", 0)
            .put("total", true)
        val root = post("/weapi/cloudsearch/pc", payload)
        val result = root.obj("result") ?: error("网易云搜索响应结构无效")
        val songs = result.array("songs")
        check(songs != null || result.long("songCount") == 0L) { "网易云没有返回搜索结果" }
        return songs.objects().mapNotNull(::candidateFromSong).distinctBy { it.key }
    }

    override suspend fun metadata(candidate: SongCandidate, fields: Set<MetadataField>): ScrapedMetadata {
        require(candidate.source == source)
        return metadata(candidate.id, fields)
    }

    suspend fun metadata(songId: Long, fields: Set<MetadataField> = MetadataField.entries.toSet()): ScrapedMetadata {
        if (fields == setOf(MetadataField.LYRICS)) return ScrapedMetadata(lyrics = downloadLyrics(songId))
        val songPayload = JSONObject()
            .put("c", "[{\"id\":$songId}]")
            .put("ids", "[$songId]")
        val song = post("/weapi/v3/song/detail", songPayload).array("songs").objects()
            .firstOrNull() ?: error("网易云没有返回歌曲详情")
        val base = candidateFromSong(song) ?: error("网易云歌曲详情结构无效")
        check(base.id == songId) { "网易云返回了不一致的歌曲详情" }
        val album = base.albumId?.takeIf { fields.any { it in setOf(MetadataField.DATE, MetadataField.TRACK, MetadataField.DISC, MetadataField.COVER) } }?.let { albumId ->
            sourceResult { post("/weapi/v1/album/$albumId", JSONObject().put("id", albumId)) }
                .getOrNull()
        }
        val albumInfo = album?.obj("album")
        val albumSongs = album?.array("songs").objects()
        val albumTrack = albumSongs.indexOfFirst { it.long("id") == songId }.takeIf { it >= 0 }?.plus(1)
        val trackNumber = base.trackNumber ?: albumTrack
        val trackTotal = albumSongs.size.takeIf { it > 0 }
        val discNumber = parseDisc(song.string("cd"))
        val discTotal = albumSongs.mapNotNull { parseDisc(it.string("cd")) }.maxOrNull()

        val lyrics = if (MetadataField.LYRICS in fields) downloadLyrics(songId) else RemoteValue.Unavailable
        val coverUrl = albumInfo?.string("picUrl") ?: base.coverUrl
        val publishTime = albumInfo?.long("publishTime")?.takeIf { it > 0 } ?: base.publishTimeMs

        return ScrapedMetadata(
            title = RemoteValue.Available(base.title),
            artists = base.artists.takeIf(List<String>::isNotEmpty)?.let { RemoteValue.Available(it) }
                ?: RemoteValue.Unavailable,
            album = base.album.takeIf(String::isNotBlank)?.let { RemoteValue.Available(it) }
                ?: RemoteValue.Unavailable,
            date = publishTime?.let(::releaseDate)?.let { RemoteValue.Available(it) } ?: RemoteValue.Unavailable,
            track = trackNumber?.takeIf { it > 0 }
                ?.let { RemoteValue.Available(TrackIndex(it, trackTotal)) } ?: RemoteValue.Unavailable,
            disc = discNumber?.takeIf { it > 0 }
                ?.let { RemoteValue.Available(TrackIndex(it, discTotal)) } ?: RemoteValue.Unavailable,
            lyrics = lyrics,
            cover = coverUrl?.takeIf { MetadataField.COVER in fields }?.let { url ->
                sourceResult { downloadCover(url) }.getOrNull()?.let { RemoteValue.Available(it) }
            } ?: RemoteValue.Unavailable,
        )
    }

    private suspend fun downloadLyrics(songId: Long): RemoteValue<String> {
        val lyric = sourceResult {
            post("/weapi/song/lyric", JSONObject().put("id", songId).put("lv", -1).put("tv", -1))
        }.getOrNull() ?: return RemoteValue.Unavailable
        if (lyric.boolean("pureMusic") == true) return RemoteValue.ConfirmedAbsent
        val merged = LyricsCodec.merge(lyric.obj("lrc")?.string("lyric"), lyric.obj("tlyric")?.string("lyric"))
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
        MusicHttp.json("https://music.163.com$path", REFERER, "网易云", body,
            contentType = "application/x-www-form-urlencoded").also {
            check(it.has("code") && it.optInt("code", -1) == 200) { "网易云接口暂不可用，请稍后重试" }
        }
    }

    private suspend fun downloadCover(rawUrl: String): CoverImage = MusicHttp.cover(rawUrl, REFERER) {
        it == "music.126.net" || it.endsWith(".music.126.net")
    }

    private fun candidateFromSong(song: JSONObject): SongCandidate? {
        val id = song.long("id")?.takeIf { it > 0 } ?: return null
        val album = song.obj("al") ?: song.obj("album")
        val artistArray = song.array("ar") ?: song.array("artists")
        return SongCandidate(
            id = id,
            title = song.string("name") ?: return null,
            artists = artistArray.objects().mapNotNull { it.string("name") },
            album = album?.string("name").orEmpty(),
            albumId = album?.long("id"),
            durationMs = song.long("dt") ?: song.long("duration"),
            coverUrl = album?.string("picUrl"),
            publishTimeMs = song.long("publishTime")?.takeIf { it > 0 },
            trackNumber = (song.long("no") ?: song.long("position"))?.toInt(),
        )
    }

    private fun releaseDate(timestamp: Long): ReleaseDate {
        val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        return ReleaseDate(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun parseDisc(value: String?): Int? = value?.trim()?.substringBefore('/')?.toIntOrNull()

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
    private fun JSONObject.array(key: String): JSONArray? = optJSONArray(key)
    private fun JSONObject.string(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotEmpty) else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.boolean(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
        emptyList()
    } else {
        buildList(length()) {
            repeat(length()) { index -> optJSONObject(index)?.let(::add) }
        }
    }

    private companion object {
        const val REFERER = "https://music.163.com/"
    }
}
