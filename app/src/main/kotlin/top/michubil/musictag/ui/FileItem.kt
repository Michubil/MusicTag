package top.michubil.musictag.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument

/** Preview changes only invalidate the observing row, not the directory list. */
class FileItem(val document: MusicDocument, track: LocalTrack? = null) {
    private val mutablePreview = MutableStateFlow(FilePreview(track, null))
    val preview = mutablePreview.asStateFlow()

    internal fun showPreview(preview: FilePreview) {
        mutablePreview.update { preview.copy(track = preview.track ?: it.track) }
    }
    internal fun releaseArtwork() { mutablePreview.update { if (it.artwork == null) it else it.copy(artwork = null) } }
    internal fun frozenCopy(): FileItem = FileItem(document).also { it.showPreview(preview.value.copy()) }
}

/** Bitmaps are display-only; snapshot the values and flow owners, without duplicating pixel buffers. */
internal fun MainUiState.freezePreviews(): MainUiState = copy(items = items.map(FileItem::frozenCopy))

internal fun MainUiState.acceptPreview(item: FileItem, preview: FilePreview): Boolean {
    if (!containsPreviewItem(item)) return false
    item.showPreview(preview)
    return true
}

internal fun MainUiState.containsPreviewItem(item: FileItem): Boolean =
    items.any { it === item } || searchItems.any { it === item } || albumItems.any { it === item } ||
        albumCovers.values.any { it === item }

internal fun MainUiState.isCachedPreview(item: FileItem): Boolean =
    (showingCachedContent && items.any { it === item }) ||
        (showingCachedLibrary && (searchItems.any { it === item } || albumItems.any { it === item } ||
            albumCovers.values.any { it === item }))
