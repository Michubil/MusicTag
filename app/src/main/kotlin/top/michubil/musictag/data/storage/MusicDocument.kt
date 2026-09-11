package top.michubil.musictag.data.storage

import top.michubil.musictag.data.model.supportedAudioExtensions
import top.michubil.musictag.data.FileSort

/** URI identity stays separate from provider display names and local working files. */
data class MusicDocument(
    val treeUri: String,
    val uri: String,
    val parentUri: String?,
    val name: String,
    val isDirectory: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null,
    val canCreate: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val relativePath: String? = null,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isAudio: Boolean get() = !isDirectory && extension in supportedAudioExtensions
    val hasReliableStats: Boolean get() = size != null && size >= 0 && modified != null && modified > 0

    fun matchesContent(name: String, size: Long?, modified: Long?): Boolean =
        hasReliableStats && this.name == name && this.size == size && this.modified == modified
}

internal fun sortDocuments(files: List<MusicDocument>, sort: FileSort, descending: Boolean): List<MusicDocument> =
    files.sortedWith { left, right ->
        if (left.isDirectory != right.isDirectory) {
            if (left.isDirectory) -1 else 1
        } else {
            val first = if (descending) right else left
            val second = if (descending) left else right
            val value = when (sort) {
                FileSort.NAME -> first.name.compareTo(second.name, ignoreCase = true)
                FileSort.TYPE -> first.extension.compareTo(second.extension, ignoreCase = true)
                FileSort.MODIFIED -> compareValues(first.modified, second.modified)
            }
            if (value != 0) value else first.name.compareTo(second.name, ignoreCase = true)
        }
    }
