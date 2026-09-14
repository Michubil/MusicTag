package top.michubil.musictag.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument

class FileItemTest {
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
