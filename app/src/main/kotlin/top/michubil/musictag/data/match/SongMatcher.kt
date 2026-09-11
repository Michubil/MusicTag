package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MatchResult
import top.michubil.musictag.data.model.SongCandidate
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

object SongMatcher {
    private data class MatchText(val title: String, val artists: List<String>, val album: String?)

    fun query(track: LocalTrack): String = listOfNotNull(
        track.title?.takeIf(String::isNotBlank) ?: track.fileName.substringBeforeLast('.'),
        track.artists.firstOrNull(),
    ).joinToString(" ")

    fun rank(track: LocalTrack, candidates: List<SongCandidate>): List<MatchResult> {
        val text = MatchText(
            track.title?.takeIf(String::isNotBlank)?.let(::comparableText) ?: comparableFileName(track.fileName),
            track.artists.map(::comparableText),
            track.album?.let(::comparableText),
        )
        return candidates.map { MatchResult(it, score(track, text, it)) }
            .sortedByDescending(MatchResult::confidence)
    }

    fun automatic(track: LocalTrack, candidates: List<SongCandidate>): MatchResult? {
        return automaticFromRanked(rank(track, candidates))
    }

    internal fun automaticFromRanked(ranked: List<MatchResult>): MatchResult? {
        val first = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        return first.takeIf { it.confidence >= 0.78 && (second == null || it.confidence - second.confidence >= 0.10) }
    }

    private fun score(local: LocalTrack, text: MatchText, remote: SongCandidate): Double {
        val title = similarity(text.title, comparableText(remote.title))
        val remoteArtists = remote.artists.map(::comparableText)
        val artist = if (text.artists.isEmpty()) 0.5 else text.artists.maxOf { left ->
            remoteArtists.maxOfOrNull { right -> similarity(left, right) } ?: 0.0
        }
        val album = text.album?.let { similarity(it, comparableText(remote.album)) } ?: 0.5
        val duration = when {
            local.durationMs == null || remote.durationMs == null -> 0.5
            else -> (1.0 - abs(local.durationMs - remote.durationMs).toDouble() / 10_000.0).coerceIn(0.0, 1.0)
        }
        return title * 0.52 + artist * 0.24 + album * 0.12 + duration * 0.12
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
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

private val extensionPattern = Regex("\\.[a-z0-9]{2,5}$")
private val punctuationPattern = Regex("[\\p{P}\\p{S}\\s]+")

internal fun comparableText(value: String): String = foldComparable(value, stripFileExtension = false)

internal fun comparableFileName(value: String): String = foldComparable(value, stripFileExtension = true)

internal fun sameRecording(first: SongCandidate, second: SongCandidate): Boolean {
    if (comparableText(first.title) != comparableText(second.title)) return false
    if (first.artists.isEmpty() || second.artists.isEmpty() ||
        first.artists.none { left -> second.artists.any { right -> comparableText(left) == comparableText(right) } }) {
        return false
    }
    if (first.album.isNotBlank() && second.album.isNotBlank() && comparableText(first.album) != comparableText(second.album)) {
        return false
    }
    if (first.durationMs != null && second.durationMs != null && abs(first.durationMs - second.durationMs) > 5_000) {
        return false
    }
    return true
}

private fun foldComparable(value: String, stripFileExtension: Boolean): String {
    val folded = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val withoutExtension = if (stripFileExtension) folded.replace(extensionPattern, "") else folded
    return withoutExtension.replace(punctuationPattern, "")
}
