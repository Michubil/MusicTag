package top.michubil.musictag.data

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument
import java.util.Locale

enum class AlbumSort { TITLE, YEAR, COUNT }

data class AlbumGroup(
    val key: String,
    val title: String,
    val artist: String?,
    val year: Int?,
    val tracks: List<MusicDocument>,
)

internal object AlbumLibrary {
    const val UnknownTitle = "未知专辑"

    fun group(entries: List<LibraryEntry>): List<AlbumGroup> = entries.groupBy(::key).map { (key, members) ->
        val title = displayTitle(members.first().track?.album)
        val artist = members.firstNotNullOfOrNull { displayArtist(it.track) }
        AlbumGroup(key, title, artist, year(members), members.map(LibraryEntry::document))
    }

    fun sort(groups: List<AlbumGroup>, sort: AlbumSort): List<AlbumGroup> = when (sort) {
        AlbumSort.TITLE -> groups.sortedWith(compareBy<AlbumGroup> { letterRank(indexLetter(it.title)) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        AlbumSort.YEAR -> groups.sortedWith(compareByDescending<AlbumGroup> { it.year ?: Int.MIN_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        AlbumSort.COUNT -> groups.sortedWith(compareByDescending<AlbumGroup> { it.tracks.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun summary(count: Int, year: Int?, artist: String?): String = buildString {
        append(count)
        append("首")
        if (year != null) {
            append(' ')
            append(year)
        }
        if (!artist.isNullOrBlank()) {
            append(' ')
            append(artist)
        }
    }

    fun indexLetter(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return "#"
        val latin = latinize(trimmed)
        val letter = latin.firstOrNull { it.isLetter() }?.uppercaseChar()
        return if (letter != null && letter in 'A'..'Z') letter.toString() else "#"
    }

    private fun key(entry: LibraryEntry): String {
        val album = entry.track?.album?.trim().orEmpty().lowercase(Locale.ROOT)
        val artist = displayArtist(entry.track)?.lowercase(Locale.ROOT).orEmpty()
        return "$album\u001f$artist"
    }

    private fun displayTitle(album: String?): String = album?.trim()?.takeIf { it.isNotEmpty() } ?: UnknownTitle

    private fun displayArtist(track: LocalTrack?): String? {
        if (track == null) return null
        return track.albumArtists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: track.artists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun year(entries: List<LibraryEntry>): Int? =
        entries.mapNotNull { it.track?.year }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun letterRank(letter: String): Int = if (letter == "#") 26 else letter.first() - 'A'

    private val hanLatin = runCatching {
        val translator = Class.forName("android.icu.text.Transliterator")
            .getMethod("getInstance", String::class.java)
            .invoke(null, "Han-Latin; Latin-ASCII")
        val method = translator.javaClass.getMethod("transliterate", String::class.java)
        translator to method
    }.getOrNull()

    private fun latinize(title: String): String {
        val loaded = hanLatin ?: return title
        return runCatching { loaded.second.invoke(loaded.first, title) as String }.getOrDefault(title)
    }
}
