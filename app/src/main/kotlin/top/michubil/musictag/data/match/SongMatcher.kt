package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MatchResult
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.model.supportedAudioExtensions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

object SongMatcher {
    private data class MatchText(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isExactTitleRequired: Boolean = false,
    ) {
        val comparableTitle = comparableText(title)
        val comparableArtists = artists.map(::comparableText).filter(String::isNotEmpty)
        val comparableAlbum = album?.let(::comparableText)?.takeIf(String::isNotEmpty)
        val versions = versionMarkers(title)
    }

    fun query(track: LocalTrack): String {
        val text = matchTexts(track).first()
        val artist = text.artists.firstOrNull()?.takeUnless {
            text.comparableTitle.contains(comparableText(it))
        }
        return listOfNotNull(text.title, artist).joinToString(" ")
    }

    fun rank(track: LocalTrack, candidates: List<SongCandidate>): List<MatchResult> {
        val texts = matchTexts(track)
        return candidates.distinctBy { it.key }.map { candidate ->
            MatchResult(candidate, texts.maxOf { score(track, it, candidate) })
        }
            .sortedByDescending(MatchResult::confidence)
    }

    fun automatic(track: LocalTrack, candidates: List<SongCandidate>): MatchResult? {
        val texts = matchTexts(track)
        val ranked = candidates.distinctBy { it.key }.mapNotNull { candidate ->
            val compatible = texts.filter { canAutomaticallyMatch(track, it, candidate) }
            compatible.maxOfOrNull { score(track, it, candidate) }?.let { MatchResult(candidate, it) }
        }.sortedByDescending(MatchResult::confidence)
        val first = ranked.firstOrNull() ?: return null
        // A corroborating record on the other platform is not a competing choice.
        // Missing albums must not join otherwise distinct releases into one choice.
        val second = ranked.drop(1).firstOrNull { !isCrossSourceEquivalent(first.candidate, it.candidate) }
        return first.takeIf { it.confidence >= 0.78 && (second == null || it.confidence - second.confidence >= 0.10) }
    }

    private fun matchTexts(track: LocalTrack): List<MatchText> {
        val taggedTitle = track.title?.trim()?.takeIf(String::isNotEmpty)
        val title = taggedTitle ?: track.fileName.replace(extensionPattern, "")
            .replace(trackNumberPattern, "").trim()
        val artists = track.artists.map(String::trim).filter { comparableText(it).isNotEmpty() }
        val base = MatchText(title, artists, track.album)
        if (taggedTitle != null) return listOf(base)
        val parts = filenameSeparator.split(title).map(String::trim)
        if (parts.size != 2 || parts.any { comparableText(it).isEmpty() }) return listOf(base)
        val inferred = if (artists.isEmpty()) {
            listOf(MatchText(parts[1], listOf(parts[0]), track.album, isExactTitleRequired = true),
                MatchText(parts[0], listOf(parts[1]), track.album, isExactTitleRequired = true))
        } else {
            parts.indices.filter { comparableText(parts[it]) in base.comparableArtists }.map {
                MatchText(parts[1 - it], artists, track.album, isExactTitleRequired = true)
            }
        }
        return listOf(base.copy(isExactTitleRequired = inferred.isNotEmpty())) + inferred
    }

    private fun canAutomaticallyMatch(local: LocalTrack, text: MatchText, remote: SongCandidate): Boolean {
        if (text.comparableTitle.isEmpty() || comparableText(remote.title).isEmpty()) return false
        if (durationDifference(local.durationMs, remote.durationMs)?.let { it > 10_000 } == true) return false
        if (text.versions != versionMarkers(remote.title)) return false
        val artists = remote.artists.map(::comparableText).filter(String::isNotEmpty)
        if (text.comparableArtists.any { it !in artists }) return false
        // A filename split is only evidence when both pieces are confirmed by a candidate.
        if (text.isExactTitleRequired && text.comparableTitle != comparableText(remote.title)) return false
        return true
    }

    private fun isCrossSourceEquivalent(first: SongCandidate, second: SongCandidate): Boolean =
        first.source != second.source && comparableText(first.album).isNotEmpty() && comparableText(second.album).isNotEmpty() &&
            first.artists.map(::comparableText).toSet() == second.artists.map(::comparableText).toSet() &&
            sameRecording(first, second)

    private fun score(local: LocalTrack, text: MatchText, remote: SongCandidate): Double {
        val title = similarity(text.comparableTitle, comparableText(remote.title))
        val remoteArtists = remote.artists.map(::comparableText)
        val artist = if (text.comparableArtists.isEmpty()) 0.5 else text.comparableArtists.maxOf { left ->
            remoteArtists.maxOfOrNull { right -> similarity(left, right) } ?: 0.0
        }
        val album = text.comparableAlbum?.let { similarity(it, comparableText(remote.album)) } ?: 0.5
        val duration = durationDifference(local.durationMs, remote.durationMs)
            ?.let { (1.0 - it / 10_000.0).coerceIn(0.0, 1.0) } ?: 0.5
        return title * 0.52 + artist * 0.24 + album * 0.12 + duration * 0.12
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1,
                )
            }
            val reusable = previous
            previous = current
            current = reusable
        }
        return previous[right.length]
    }
}

private val extensionPattern = Regex("\\.(?:${supportedAudioExtensions.joinToString("|") { Regex.escape(it) }})$",
    RegexOption.IGNORE_CASE)
private val punctuationPattern = Regex("[\\p{P}\\p{S}\\s]+")
private val trackNumberPattern = Regex("^\\s*\\d{1,3}(?:[.、]\\s+|\\s+[-–—]\\s+)")
private val filenameSeparator = Regex("\\s+[-–—]\\s+")
private val versionAnnotation = Regex("[\\[(【]([^\\])】]+)[\\])】]")
private val versionPatterns = listOf(
    Regex("(?<![a-z])live(?![a-z])|现场|現場|演唱会|演唱會"),
    Regex("(?<![a-z])demo(?![a-z])|小样|小樣"),
    Regex("(?<![a-z])remix(?![a-z])|混音"),
    Regex("(?<![a-z])(?:instrumental|karaoke)(?![a-z])|伴奏|纯音乐|純音樂"),
    Regex("(?<![a-z])(?:acoustic|unplugged)(?![a-z])|不插电|不插電"),
    Regex("(?<![a-z])remaster(?:ed)?(?![a-z])|重制|重製"),
)

private fun versionMarkers(title: String): Set<Int> {
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    val annotations = versionAnnotation.findAll(normalized).map { it.groupValues[1] }.toList()
    return versionPatterns.indices.filterTo(mutableSetOf()) { index ->
        annotations.any { versionPatterns[index].containsMatchIn(it) } ||
            versionPatterns[index].findAll(normalized).any { match ->
                val start = match.range.first
                start > 0 && normalized.substring(match.range.last + 1).trim() in setOf("", "版") &&
                    (match.value.first().code > 127 || !normalized[start - 1].isLetterOrDigit())
            }
    }
}

private fun durationDifference(first: Long?, second: Long?): Double? =
    if (first == null || second == null || first <= 0 || second <= 0) null
    else abs(first.toDouble() - second.toDouble())

internal fun comparableText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(punctuationPattern, "")

internal fun sameRecording(first: SongCandidate, second: SongCandidate): Boolean {
    val title = comparableText(first.title)
    if (title.isEmpty() || title != comparableText(second.title)) return false
    if (first.artists.isEmpty() || second.artists.isEmpty() ||
        first.artists.none { left -> comparableText(left).isNotEmpty() &&
            second.artists.any { right -> comparableText(left) == comparableText(right) } }) {
        return false
    }
    if (first.album.isNotBlank() && second.album.isNotBlank() && comparableText(first.album) != comparableText(second.album)) {
        return false
    }
    if (durationDifference(first.durationMs, second.durationMs)?.let { it > 5_000 } == true) {
        return false
    }
    return true
}
