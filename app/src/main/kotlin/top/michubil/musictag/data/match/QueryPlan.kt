package top.michubil.musictag.data.match

import top.michubil.musictag.data.model.LocalTrack

internal object QueryPlan {
    fun display(track: LocalTrack, user: UserQuery?): Pair<String, String> {
        val reading = readings(track, user).firstOrNull { !it.requiresBoth } ?: return "" to ""
        return reading.title to reading.artists.joinToString(" / ")
    }

    fun plannedQueries(track: LocalTrack, user: UserQuery?): List<String> {
        val primary = readings(track, user).firstOrNull { !it.requiresBoth } ?: return emptyList()
        val queries = mutableListOf<String>()
        fun add(value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isNotEmpty() && queries.none { comparableText(it) == comparableText(text) }) queries += text
        }
        if (primary.artistsTrusted) add(primary.title + " " + primary.artists.joinToString(" "))
        add(primary.title)
        add(titleBody(primary.title))
        if (user == null) {
            val filename = cleanedFileTitle(track.fileName)
            if (track.title?.let(::isCredibleTitle) == true && filenameParts(filename) == null) add(filename)
            filenameParts(filename)?.let { parts ->
                add(parts[0])
                add(parts[1])
            }
        }
        add(titleSubtitle(primary.title))
        val body = titleBody(primary.title)
        val folded = ScriptForms.simplified(body)
        if (comparableText(folded) != comparableText(body)) add(folded)
        return queries
    }
}
