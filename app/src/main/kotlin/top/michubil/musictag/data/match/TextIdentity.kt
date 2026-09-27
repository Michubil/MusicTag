package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.supportedAudioExtensions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal enum class TitleRelation { EXACT, VARIANT, WEAK, NONE }

internal enum class ArtistRelation { MATCH, UNKNOWN, INCOMPLETE, CONFLICT }

internal enum class VersionRelation { SAME, UNSPECIFIED, ONE_SIDED, CONFLICT }

internal enum class DurationRelation { CLOSE, UNKNOWN, CONFLICT }

internal enum class AlbumRelation { SAME, UNKNOWN, DIFFERENT }

internal data class Reading(
    val title: String,
    val artists: List<String>,
    val artistsTrusted: Boolean,
    val requiresBoth: Boolean,
)

internal data class Evidence(
    val title: TitleRelation,
    val artist: ArtistRelation,
    val version: VersionRelation,
    val duration: DurationRelation,
    val album: AlbumRelation,
    val filenameConfirmed: Boolean,
)

private val extensionPattern = Regex("\\.(?:${supportedAudioExtensions.joinToString("|") { Regex.escape(it) }})$", RegexOption.IGNORE_CASE)
private val punctuationPattern = Regex("[\\p{P}\\p{S}\\s]+")
private val trackNumberPattern = Regex("^\\s*\\d{1,3}(?:[.、]\\s+|\\s+[-–—]\\s+)")
private val filenameSeparator = Regex("\\s+[-–—]\\s+")
private val trailingSubtitle = Regex("\\s+[-–—]([^-–—\\s]+(?:\\s+[^-–—\\s]+)+)[-–—]\\s*$")
private val trailingAnnotation = Regex("\\s*[(\\[（【]([^()\\[\\]（）【】]+)[)\\]）】]\\s*$")
private val versionAnnotation = Regex("[\\[(【]([^\\])】]+)[\\])】]")
private val versionPatterns = listOf(
    Regex("(?<![a-z])live(?![a-z])|现场|現場|演唱会|演唱會"),
    Regex("(?<![a-z])demo(?![a-z])|小样|小樣"),
    Regex("(?<![a-z])remix(?![a-z])|混音"),
    Regex("(?<![a-z])(?:instrumental|karaoke)(?![a-z])|伴奏|纯音乐|純音樂"),
    Regex("(?<![a-z])(?:acoustic|unplugged)(?![a-z])|不插电|不插電"),
    Regex("(?<![a-z])remaster(?:ed)?(?![a-z])|重制|重製"),
)
private val placeholders = setOf("unknown", "未知", "na", "null", "none")
private val cjk = Regex("\\p{IsHan}|\\p{IsHiragana}|\\p{IsKatakana}")

internal object ScriptForms {
    private val simplified: ((String) -> String)? = try {
        val transliterator = android.icu.text.Transliterator.getInstance("Traditional-Simplified")
        val fold: (String) -> String = { value -> transliterator.transliterate(value) }
        fold
    } catch (_: Exception) {
        null
    }

    fun simplified(value: String): String {
        if (value.any(::isKana)) return value
        return simplified?.invoke(value) ?: value
    }
}

internal fun comparableText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(punctuationPattern, "")

/** Comparison form: simplified Chinese, then the same punctuation folding. Kana text stays unchanged. */
internal fun identityKey(value: String): String = comparableText(ScriptForms.simplified(value))

internal fun isCredibleTitle(value: String): Boolean {
    val comparable = comparableText(value)
    return comparable.isNotEmpty() && comparable !in placeholders
}

internal fun cleanedFileTitle(fileName: String): String =
    fileName.replace(extensionPattern, "").replace(trackNumberPattern, "").trim()

internal fun titleBody(title: String): String {
    val match = trailingSubtitle.find(title) ?: return title.trim()
    if (isVersionText(match.groupValues[1])) return title.trim()
    return title.substring(0, match.range.first).trim().ifEmpty { title.trim() }
}

/** Drops a trailing note such as `(《活俠傳》遊戲配樂)`. Version marks in parentheses stay. */
internal fun titleCore(title: String): String {
    var current = titleBody(title).trim()
    while (true) {
        val match = trailingAnnotation.find(current) ?: return current
        if (isVersionText(match.groupValues[1])) return current
        val stripped = current.substring(0, match.range.first).trim()
        if (stripped.isEmpty()) return current
        current = stripped
    }
}

internal fun titleSubtitle(title: String): String? {
    val match = trailingSubtitle.find(title) ?: return null
    val inner = match.groupValues[1].trim()
    if (inner.isEmpty() || isVersionText(inner)) return null
    return inner
}

internal fun filenameParts(title: String): List<String>? {
    val parts = filenameSeparator.split(title).map(String::trim)
    if (parts.size != 2 || parts.any { comparableText(it).isEmpty() }) return null
    return parts
}

/** A slash between Latin words stays intact (`AC/DC`). CJK credits may be a split hypothesis. */
internal fun splitArtistValue(value: String): List<String>? {
    val pieces = value.split(Regex("\\s*[/、&]\\s*|\\s+feat\\.\\s+", RegexOption.IGNORE_CASE))
        .map(String::trim).filter { it.isNotEmpty() }
    if (pieces.size < 2 || pieces.any { comparableText(it).isEmpty() }) return null
    if (value.contains('/') && pieces.all { !cjk.containsMatchIn(it) }) return null
    return pieces
}

internal fun versionMarks(title: String): Set<Int> {
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    val annotations = versionAnnotation.findAll(normalized).map { it.groupValues[1] }.toList()
    val marks = versionPatterns.indices.filterTo(mutableSetOf()) { index ->
        annotations.any { versionPatterns[index].containsMatchIn(it) } ||
            versionPatterns[index].findAll(normalized).any { match ->
                val start = match.range.first
                start > 0 && normalized.substring(match.range.last + 1).trim() in setOf("", "版") &&
                    (match.value.first().code > 127 || !normalized[start - 1].isLetterOrDigit())
            }
    }
    val subtitle = trailingSubtitle.find(title)?.groupValues?.get(1) ?: return marks
    val subtitleText = Normalizer.normalize(subtitle, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    versionPatterns.indices.filterTo(marks) { versionPatterns[it].containsMatchIn(subtitleText) }
    return marks
}

internal fun titleRelation(local: String, remote: String): TitleRelation {
    val left = comparableText(local)
    val right = comparableText(remote)
    if (left.isEmpty() || right.isEmpty()) return TitleRelation.NONE
    if (left == right) return TitleRelation.EXACT
    val leftCore = comparableText(titleCore(local))
    val rightCore = comparableText(titleCore(remote))
    if (leftCore.isNotEmpty() && (leftCore == rightCore || leftCore == right || rightCore == left)) return TitleRelation.VARIANT
    val leftKey = identityKey(titleCore(local))
    val rightKey = identityKey(titleCore(remote))
    if (leftKey.isNotEmpty() && (leftKey == rightKey || leftKey == identityKey(remote) || rightKey == identityKey(local))) {
        return TitleRelation.VARIANT
    }
    val foldedLeft = identityKey(local)
    val foldedRight = identityKey(remote)
    if (foldedLeft.length >= 2 && foldedRight.length >= 2 && (foldedLeft in foldedRight || foldedRight in foldedLeft)) {
        return TitleRelation.WEAK
    }
    if (left.length >= 2 && right.length >= 2 && (left in right || right in left)) return TitleRelation.WEAK
    return TitleRelation.NONE
}

internal fun artistRelation(local: List<String>, remote: List<String>, trusted: Boolean): ArtistRelation {
    val left = local.map(::identityKey).filter { it.isNotEmpty() }
    val right = remote.map(::identityKey).filter { it.isNotEmpty() }.toSet()
    if (!trusted || left.isEmpty()) return ArtistRelation.UNKNOWN
    if (right.isEmpty() || left.none { it in right }) return ArtistRelation.CONFLICT
    if (left.all { it in right }) return ArtistRelation.MATCH
    return ArtistRelation.INCOMPLETE
}

internal fun versionRelation(localTitle: String, remoteTitle: String): VersionRelation {
    val left = versionMarks(localTitle)
    val right = versionMarks(remoteTitle)
    return when {
        left.isEmpty() && right.isEmpty() -> VersionRelation.UNSPECIFIED
        left == right -> VersionRelation.SAME
        left.isEmpty() || right.isEmpty() -> VersionRelation.ONE_SIDED
        else -> VersionRelation.CONFLICT
    }
}

internal fun durationRelation(local: Long?, remote: Long?): DurationRelation {
    val delta = durationDelta(local, remote) ?: return DurationRelation.UNKNOWN
    return if (delta > 10_000) DurationRelation.CONFLICT else DurationRelation.CLOSE
}

internal fun albumRelation(local: String?, remote: String): AlbumRelation {
    val left = local?.let(::identityKey)?.takeIf { it.isNotEmpty() } ?: return AlbumRelation.UNKNOWN
    val right = identityKey(remote)
    if (right.isEmpty()) return AlbumRelation.UNKNOWN
    return if (left == right) AlbumRelation.SAME else AlbumRelation.DIFFERENT
}

internal fun durationDelta(first: Long?, second: Long?): Double? =
    if (first == null || second == null || first <= 0 || second <= 0) null
    else abs(first.toDouble() - second.toDouble())

internal fun readings(track: LocalTrack, user: UserQuery?): List<Reading> {
    if (user != null) {
        val title = user.title.trim()
        val artists = user.artists.map(String::trim).filter { comparableText(it).isNotEmpty() }
        return expandArtists(Reading(title, artists, artistsTrusted = artists.isNotEmpty(), requiresBoth = false))
    }
    val tag = track.title?.trim()?.takeIf(::isCredibleTitle)
    val artists = track.artists.map(String::trim).filter { comparableText(it).isNotEmpty() && comparableText(it) !in placeholders }
    val fileTitle = cleanedFileTitle(track.fileName)
    val readings = mutableListOf<Reading>()
    if (tag != null) readings += Reading(tag, artists, artistsTrusted = artists.isNotEmpty(), requiresBoth = false)
    else if (fileTitle.isNotEmpty()) readings += Reading(fileTitle, artists, artistsTrusted = artists.isNotEmpty(), requiresBoth = false)
    filenameParts(if (tag == null) fileTitle else cleanedFileTitle(track.fileName))?.let { parts ->
        if (artists.isEmpty()) {
            readings += Reading(parts[1], listOf(parts[0]), artistsTrusted = true, requiresBoth = true)
            readings += Reading(parts[0], listOf(parts[1]), artistsTrusted = true, requiresBoth = true)
        } else {
            val known = artists.map(::comparableText).toSet()
            parts.forEachIndexed { index, part ->
                if (comparableText(part) in known) {
                    readings += Reading(parts[1 - index], artists, artistsTrusted = true, requiresBoth = true)
                }
            }
        }
    }
    return readings.flatMap(::expandArtists).distinct()
}

internal fun conflictingInputs(track: LocalTrack, user: UserQuery?): Boolean {
    if (user != null) return false
    val tag = track.title?.trim()?.takeIf(::isCredibleTitle) ?: return false
    val artists = track.artists.map(::comparableText).filter { it.isNotEmpty() && it !in placeholders }
    if (artists.isEmpty()) return false
    val parts = filenameParts(cleanedFileTitle(track.fileName)) ?: return false
    val tagTitle = comparableText(tag)
    val agrees = listOf(0, 1).any { index ->
        comparableText(parts[index]) in artists && comparableText(parts[1 - index]) == tagTitle
    }
    return !agrees
}

internal fun evidenceFor(track: LocalTrack, reading: Reading, candidate: SongCandidate): Evidence {
    val title = titleRelation(reading.title, candidate.title)
    val artist = artistRelation(reading.artists, candidate.artists, reading.artistsTrusted)
    val confirmed = reading.requiresBoth && title == TitleRelation.EXACT && artist == ArtistRelation.MATCH
    return Evidence(
        title = title,
        artist = artist,
        version = versionRelation(reading.title, candidate.title),
        duration = durationRelation(track.durationMs, candidate.durationMs),
        album = albumRelation(track.album, candidate.album),
        filenameConfirmed = confirmed,
    )
}

internal fun bestEvidence(track: LocalTrack, candidate: SongCandidate, user: UserQuery?): Evidence? {
    val options = readings(track, user)
    if (options.isEmpty()) return null
    return options.map { evidenceFor(track, it, candidate) }.maxWith(evidenceOrder)
}

internal fun sameRecording(first: SongCandidate, second: SongCandidate): Boolean {
    if (versionMarks(first.title) != versionMarks(second.title)) return false
    val title = titleRelation(first.title, second.title)
    if (title != TitleRelation.EXACT && title != TitleRelation.VARIANT) return false
    val left = first.artists.map(::identityKey).filter { it.isNotEmpty() }.toSet()
    val right = second.artists.map(::identityKey).filter { it.isNotEmpty() }.toSet()
    if (left.isEmpty() || left != right) return false
    val delta = durationDelta(first.durationMs, second.durationMs) ?: return false
    return delta <= 10_000
}

internal fun sameRelease(release: SongCandidate, other: SongCandidate): Boolean {
    if (release.key == other.key) return true
    if (!sameRecording(release, other)) return false
    val album = identityKey(release.album)
    if (album.isEmpty() || album != identityKey(other.album)) return false
    val left = release.trackNumber
    val right = other.trackNumber
    return left == null || right == null || left == right
}

internal fun explanations(evidence: Evidence): List<String> = listOfNotNull(
    when (evidence.title) {
        TitleRelation.EXACT -> "标题一致"
        TitleRelation.VARIANT -> "标题变体"
        TitleRelation.WEAK -> "标题相近"
        TitleRelation.NONE -> "标题不对应"
    },
    when (evidence.artist) {
        ArtistRelation.MATCH -> "艺术家对应"
        ArtistRelation.UNKNOWN -> "署名不足"
        ArtistRelation.INCOMPLETE -> "署名未完全对应"
        ArtistRelation.CONFLICT -> "署名不同"
    },
    when (evidence.version) {
        VersionRelation.SAME -> "版本标记相同"
        VersionRelation.UNSPECIFIED -> null
        VersionRelation.ONE_SIDED -> "版本待确认"
        VersionRelation.CONFLICT -> "版本冲突"
    },
    when (evidence.duration) {
        DurationRelation.CLOSE -> null
        DurationRelation.UNKNOWN -> "时长未知"
        DurationRelation.CONFLICT -> "时长不符"
    },
    when (evidence.album) {
        AlbumRelation.SAME -> null
        AlbumRelation.UNKNOWN -> null
        AlbumRelation.DIFFERENT -> "发行待确认"
    },
    "文件名歌名和歌手均已确认".takeIf { evidence.filenameConfirmed },
)

private val evidenceOrder = compareBy<Evidence>(
    { it.title == TitleRelation.EXACT || it.title == TitleRelation.VARIANT },
    { it.artist == ArtistRelation.MATCH },
    { it.filenameConfirmed },
    { it.title == TitleRelation.EXACT },
    { it.version != VersionRelation.CONFLICT && it.version != VersionRelation.ONE_SIDED },
    { it.duration != DurationRelation.CONFLICT },
)

private fun expandArtists(reading: Reading): List<Reading> {
    val split = reading.artists.mapNotNull(::splitArtistValue).firstOrNull() ?: return listOf(reading)
    return listOf(reading, reading.copy(artists = split))
}

private fun isVersionText(value: String): Boolean {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return versionPatterns.any { it.containsMatchIn(normalized) }
}

private fun isKana(char: Char): Boolean {
    val script = Character.UnicodeScript.of(char.code)
    return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
}
