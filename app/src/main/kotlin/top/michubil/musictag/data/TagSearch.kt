package top.michubil.musictag.data

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameTag
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.sortDocuments
import java.text.Normalizer
import java.util.Locale

internal data class SearchEntry(
    val document: MusicDocument,
    val fields: List<String>,
    val track: LocalTrack?,
)

internal object TagSearch {
    fun tokens(query: String): List<String> = query.trim().split(Regex("\\s+"))
        .map(::normalize).filter(String::isNotEmpty)

    fun matches(query: String, fields: Iterable<String>): Boolean {
        val needles = tokens(query)
        if (needles.isEmpty()) return false
        val haystack = fields.joinToString("\n", transform = ::normalize)
        return needles.all { it in haystack }
    }

    fun fields(fileName: String, metadata: AudioTextMetadata): List<String> = buildList {
        add(fileName)
        addAll(metadata.values.values)
        metadata.date?.let(::add)
    }

    fun track(fileName: String, metadata: AudioTextMetadata): LocalTrack = LocalTrack(
        fileName = fileName,
        title = metadata.values[RenameTag.TITLE],
        artists = metadata.artists,
        album = metadata.values[RenameTag.ALBUM],
        durationMs = null,
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

internal fun filterSearch(
    index: List<SearchEntry>,
    query: String,
    sort: FileSort,
    descending: Boolean,
): List<SearchEntry> {
    val matched = index.filter { TagSearch.matches(query, it.fields) }
    val byUri = matched.associateBy { it.document.uri }
    return sortDocuments(matched.map(SearchEntry::document), sort, descending).map { byUri.getValue(it.uri) }
}
