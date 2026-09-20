package top.michubil.musictag.data.model

import android.graphics.BitmapFactory
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth


enum class MetadataField(val label: String) {
    COVER("封面"),
    LYRICS("歌词"),
    TITLE("标题"),
    ARTISTS("艺术家"),
    ALBUM("专辑"),
    DATE("发行日期"),
    TRACK("音轨号"),
    DISC("碟号"),
    ;

    companion object {
        val textFields = listOf(TITLE, ARTISTS, ALBUM, DATE, TRACK, DISC, LYRICS)
    }
}

internal fun artistLines(values: List<String>): String =
    values.map(String::trim).filter(String::isNotEmpty).joinToString("\n")

internal fun parseArtistLines(text: String): List<String> =
    text.lines().map(String::trim).filter(String::isNotEmpty)

data class FieldPolicy(
    val enabled: Boolean = true,
    val overwrite: Boolean = true,
)

internal fun FieldPolicy.shouldWrite(incoming: RemoteValue<*>, hasExisting: Boolean): Boolean = enabled && when (incoming) {
    is RemoteValue.Available -> overwrite || !hasExisting
    RemoteValue.ConfirmedAbsent -> overwrite
    RemoteValue.Unavailable -> false
}

internal inline fun <T> FieldPolicy.writeChange(
    incoming: RemoteValue<T>,
    hasExisting: Boolean,
    write: (T) -> Unit,
    clear: () -> Unit,
) {
    if (!shouldWrite(incoming, hasExisting)) return
    when (incoming) {
        is RemoteValue.Available -> write(incoming.value)
        RemoteValue.ConfirmedAbsent -> clear()
        RemoteValue.Unavailable -> Unit
    }
}

enum class Mp3TagVersion(val major: Int, val label: String) {
    V23(3, "ID3v2.3"),
    V24(4, "ID3v2.4"),
}

enum class MusicSource(val label: String) { NETEASE("网易云音乐"), QQ("QQ 音乐") }

enum class SourceOrder(val label: String, val sources: List<MusicSource>) {
    NETEASE_FIRST("网易云 → QQ 音乐", listOf(MusicSource.NETEASE, MusicSource.QQ)),
    QQ_FIRST("QQ 音乐 → 网易云", listOf(MusicSource.QQ, MusicSource.NETEASE)),
    NETEASE_ONLY("仅网易云音乐", listOf(MusicSource.NETEASE)),
    QQ_ONLY("仅 QQ 音乐", listOf(MusicSource.QQ)),
}

enum class MetadataGroup(val label: String, val fields: Set<MetadataField>) {
    TAGS("组合源", setOf(MetadataField.TITLE, MetadataField.ARTISTS, MetadataField.ALBUM,
        MetadataField.DATE, MetadataField.TRACK, MetadataField.DISC)),
    LYRICS("歌词源", setOf(MetadataField.LYRICS)),
    COVER("图片源", setOf(MetadataField.COVER)),
}

data class ScrapeSources(
    val tags: SourceOrder = SourceOrder.NETEASE_FIRST,
    val lyrics: SourceOrder = SourceOrder.NETEASE_FIRST,
    val cover: SourceOrder = SourceOrder.NETEASE_FIRST,
) {
    operator fun get(group: MetadataGroup): SourceOrder = when (group) {
        MetadataGroup.TAGS -> tags
        MetadataGroup.LYRICS -> lyrics
        MetadataGroup.COVER -> cover
    }

}

data class ScrapeOptions(
    val policies: Map<MetadataField, FieldPolicy> = MetadataField.entries.associateWith { FieldPolicy() },
    val formatLyricsTimeline: Boolean = true,
    val mp3TagVersion: Mp3TagVersion = Mp3TagVersion.V24,
    val sources: ScrapeSources = ScrapeSources(),
)

sealed interface RemoteValue<out T> {
    data class Available<T>(val value: T) : RemoteValue<T>
    data object ConfirmedAbsent : RemoteValue<Nothing>
    data object Unavailable : RemoteValue<Nothing>
}

fun <T, R> RemoteValue<T>.map(transform: (T) -> R): RemoteValue<R> = when (this) {
    is RemoteValue.Available -> RemoteValue.Available(transform(value))
    RemoteValue.ConfirmedAbsent -> RemoteValue.ConfirmedAbsent
    RemoteValue.Unavailable -> RemoteValue.Unavailable
}

data class ReleaseDate(
    val year: Int,
    val month: Int?,
    val day: Int?,
) {
    fun asTagValue(): String = buildString {
        append("%04d".format(java.util.Locale.ROOT, year))
        month?.let { append("-%02d".format(java.util.Locale.ROOT, it)) }
        day?.let { append("-%02d".format(java.util.Locale.ROOT, it)) }
    }

    companion object {
        private val TAG_PATTERN = Regex("[0-9]{4}(-[0-9]{2}){0,2}")

        fun parse(value: String): ReleaseDate {
            require(TAG_PATTERN.matches(value)) { "日期格式：YYYY、YYYY-MM 或 YYYY-MM-DD" }
            val parts = value.split('-').map(String::toInt)
            require(parts[0] in 1..9999) { "年份须为 0001 至 9999" }
            try {
                if (parts.size >= 2) YearMonth.of(parts[0], parts[1])
                if (parts.size == 3) LocalDate.of(parts[0], parts[1], parts[2])
            } catch (_: DateTimeException) {
                throw IllegalArgumentException("日期无效")
            }
            return ReleaseDate(parts[0], parts.getOrNull(1), parts.getOrNull(2))
        }

        fun parseOrNull(value: String): ReleaseDate? = runCatching { parse(value) }.getOrNull()
    }
}

data class TrackIndex(val number: Int, val total: Int?) {
    fun asTagValue(): String = total?.let { number.toString() + "/" + it } ?: number.toString()

    companion object {
        fun parse(value: String, label: String): TrackIndex {
            val parts = value.split('/').map { it.trim().toIntOrNull() }
            require(parts.size in 1..2 && parts.all { it != null && it > 0 } &&
                (parts.size == 1 || requireNotNull(parts[1]) >= requireNotNull(parts[0]))) {
                "$label 格式：正整数或 编号/总数，总数不能小于编号"
            }
            return TrackIndex(requireNotNull(parts[0]), parts.getOrNull(1))
        }
    }
}

data class CoverImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

internal object CoverImages {
    const val MAX_BYTES = 15 * 1024 * 1024

    fun read(bytes: ByteArray): CoverImage {
        require(bytes.size <= MAX_BYTES) { "封面不能超过 15 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png")) {
            "请选择有效的 JPEG 或 PNG 图片"
        }
        return CoverImage(bytes, bounds.outMimeType, bounds.outWidth, bounds.outHeight)
    }
}

data class ScrapedMetadata(
    val title: RemoteValue<String> = RemoteValue.Unavailable,
    val artists: RemoteValue<List<String>> = RemoteValue.Unavailable,
    val album: RemoteValue<String> = RemoteValue.Unavailable,
    val date: RemoteValue<ReleaseDate> = RemoteValue.Unavailable,
    val track: RemoteValue<TrackIndex> = RemoteValue.Unavailable,
    val disc: RemoteValue<TrackIndex> = RemoteValue.Unavailable,
    val lyrics: RemoteValue<String> = RemoteValue.Unavailable,
    val cover: RemoteValue<CoverImage> = RemoteValue.Unavailable,
)

internal fun ScrapedMetadata.value(field: MetadataField): RemoteValue<*> = when (field) {
    MetadataField.TITLE -> title
    MetadataField.ARTISTS -> artists
    MetadataField.ALBUM -> album
    MetadataField.DATE -> date
    MetadataField.TRACK -> track
    MetadataField.DISC -> disc
    MetadataField.LYRICS -> lyrics
    MetadataField.COVER -> cover
}

internal fun ScrapedMetadata.textValues(): Map<MetadataField, RemoteValue<List<String>>> = mapOf(
    MetadataField.TITLE to title.map(::listOf),
    MetadataField.ARTISTS to artists,
    MetadataField.ALBUM to album.map(::listOf),
    MetadataField.DATE to date.map { listOf(it.asTagValue()) },
    MetadataField.TRACK to track.map { listOf(it.asTagValue()) },
    MetadataField.DISC to disc.map { listOf(it.asTagValue()) },
    MetadataField.LYRICS to lyrics.map(::listOf),
)

/** Missing data and failed requests never override successful values or become a deletion. */
internal fun ScrapedMetadata.merge(other: ScrapedMetadata, fields: Set<MetadataField>, fallback: Boolean): ScrapedMetadata {
    fun <T> pick(field: MetadataField, first: RemoteValue<T>, next: RemoteValue<T>): RemoteValue<T> = when {
        field !in fields -> first
        !fallback -> next
        first is RemoteValue.Available -> first
        next is RemoteValue.Available -> next
        first == RemoteValue.ConfirmedAbsent && next == RemoteValue.ConfirmedAbsent -> RemoteValue.ConfirmedAbsent
        else -> RemoteValue.Unavailable
    }
    return ScrapedMetadata(
        pick(MetadataField.TITLE, title, other.title), pick(MetadataField.ARTISTS, artists, other.artists),
        pick(MetadataField.ALBUM, album, other.album), pick(MetadataField.DATE, date, other.date),
        pick(MetadataField.TRACK, track, other.track), pick(MetadataField.DISC, disc, other.disc),
        pick(MetadataField.LYRICS, lyrics, other.lyrics), pick(MetadataField.COVER, cover, other.cover),
    )
}

data class LocalTrack(
    val fileName: String,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    val year: Int? = null,
    val albumArtists: List<String> = emptyList(),
)

data class SongCandidate(
    val id: Long,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumId: Long?,
    val durationMs: Long?,
    val coverUrl: String?,
    val publishTimeMs: Long?,
    val trackNumber: Int?,
    val source: MusicSource = MusicSource.NETEASE,
    val mid: String? = null,
) {
    val key: String get() = "${source.name}:$id"
}

data class MatchResult(
    val candidate: SongCandidate,
    val confidence: Double,
)

internal val supportedAudioExtensions: Set<String> = setOf("flac", "mp3", "wav")
