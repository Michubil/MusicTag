package top.michubil.musictag.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument

class FileItemTest {
    @Test
    fun failedPreviewKeepsTheAlreadyIndexedTrack() {
        val document = MusicDocument("tree", "song", "parent", "song.flac")
        val track = LocalTrack(document.name, "Song", listOf("Artist"), "Album", null)
        val item = FileItem(document, track)
        item.showPreview(FilePreview(null, null))
        assertEquals(track, item.preview.value.track)
        val refreshed = track.copy(title = "Updated")
        item.showPreview(FilePreview(refreshed, null))
        assertEquals(refreshed, item.preview.value.track)
    }

    @Test
    fun cachedLibraryPreviewsNeverReadAudioBeforeValidation() {
        val document = MusicDocument("tree", "song", "parent", "song.flac")
        val directoryRow = FileItem(document)
        val albumRow = FileItem(document)
        val searchRow = FileItem(document)
        val cover = FileItem(document)
        val cached = MainUiState(showingCachedLibrary = true, items = listOf(directoryRow),
            searchItems = listOf(searchRow), albumItems = listOf(albumRow), albumCovers = mapOf("album" to cover))
        assertFalse(cached.isCachedPreview(directoryRow))
        for (item in listOf(albumRow, searchRow, cover)) {
            assertTrue(cached.isCachedPreview(item))
            assertFalse(cached.copy(showingCachedLibrary = false).isCachedPreview(item))
        }
        assertTrue(cached.copy(showingCachedContent = true).isCachedPreview(directoryRow))
        assertFalse(cached.isCachedPreview(FileItem(document)))
    }

    @Test
    fun searchRowsAcceptPreviewsButDetachedRowsWithTheSameUriDoNot() {
        val document = MusicDocument("tree", "song", "parent", "song.mp3")
        val active = FileItem(document)
        val stale = FileItem(document)
        val state = MainUiState(searching = true, searchItems = listOf(active))
        val preview = FilePreview(LocalTrack(document.name, "Song", emptyList(), null, null), null)
        assertTrue(state.containsPreviewItem(active))
        assertTrue(state.acceptPreview(active, preview))
        assertEquals(preview, active.preview.value)
        assertFalse(state.containsPreviewItem(stale))
        assertFalse(state.acceptPreview(stale, preview))
        assertNull(stale.preview.value.track)
        assertFalse(state.copy(searchItems = emptyList()).acceptPreview(active, preview))
        val albumRow = FileItem(document)
        assertTrue(state.copy(searching = false, albumItems = listOf(albumRow)).containsPreviewItem(albumRow))
        assertTrue(state.copy(searching = false, albumCovers = mapOf("album" to albumRow)).containsPreviewItem(albumRow))
    }

    @Test
    fun previewUpdatesKeepTheDirectoryAndOtherRowsUnchanged() {
        val first = FileItem(MusicDocument("tree", "one", "parent", "one.mp3"))
        val second = FileItem(MusicDocument("tree", "two", "parent", "two.mp3"))
        val state = MainUiState(items = listOf(first, second))
        val items = state.items
        val track = LocalTrack("one.mp3", "One", listOf("Artist"), "Album", null)
        first.showPreview(FilePreview(track, null))
        assertSame(items, state.items)
        assertEquals(track, first.preview.value.track)
        assertNull(second.preview.value.track)
        first.releaseArtwork()
        assertEquals(track, first.preview.value.track)
    }
}
