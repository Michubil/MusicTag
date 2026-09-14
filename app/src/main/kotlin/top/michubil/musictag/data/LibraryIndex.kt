package top.michubil.musictag.data

import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameTag
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.sortDocuments
import java.text.Normalizer
import java.util.Locale

internal class LibraryEntry(
    val document: MusicDocument,
    val searchText: String,
    val track: LocalTrack?,
) {
    constructor(document: MusicDocument, fields: Iterable<String>, track: LocalTrack?) :
        this(document, TagSearch.normalizeFields(fields), track)
}

/** Search and albums share a snapshot; disk snapshots remain read-only until SAF validation. */
internal class LibraryIndex(val entries: List<LibraryEntry>, val isCached: Boolean = false) {
    private val byUri = entries.associateBy { it.document.uri }
    private val groups = AlbumLibrary.group(entries)
    private val albumOrders = AlbumSort.entries.associateWith { sort -> lazy { AlbumLibrary.sort(groups, sort) } }

    fun albums(sort: AlbumSort): List<AlbumGroup> = albumOrders.getValue(sort).value

    fun track(document: MusicDocument): LocalTrack? =
        byUri[document.uri]?.takeIf { it.document == document }?.track
}

internal object TagSearch {
    private val whitespace = Regex("\\s+")

    fun tokens(query: String): List<String> = query.trim().split(whitespace)
        .map(::normalize).filter(String::isNotEmpty)

    fun normalizeFields(fields: Iterable<String>): String = fields.joinToString("\n", transform = ::normalize)

    fun matches(tokens: List<String>, searchText: String): Boolean =
        tokens.isNotEmpty() && tokens.all { it in searchText }

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
        year = metadata.values[RenameTag.YEAR]?.toIntOrNull(),
        albumArtists = metadata.albumArtists,
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

internal fun filterSearch(
    index: LibraryIndex,
    query: String,
    sort: FileSort,
    descending: Boolean,
    checkActive: () -> Unit = {},
): List<LibraryEntry> {
    val tokens = TagSearch.tokens(query)
    if (tokens.isEmpty()) return emptyList()
    val matched = index.entries.filter {
        checkActive()
        TagSearch.matches(tokens, it.searchText)
    }
    val byUri = matched.associateBy { it.document.uri }
    return sortDocuments(matched.map(LibraryEntry::document), sort, descending).map {
        checkActive()
        byUri.getValue(it.uri)
    }
}
