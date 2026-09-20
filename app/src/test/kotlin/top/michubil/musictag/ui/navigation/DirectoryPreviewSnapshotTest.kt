package top.michubil.musictag.ui.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.ui.FileItem
import top.michubil.musictag.ui.MainUiState
import top.michubil.musictag.ui.acceptPreview
import top.michubil.musictag.ui.freezePreviews

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DirectoryPreviewSnapshotTest {
    private val directory = MusicDocument("tree", "folder", null, "Album", isDirectory = true)
    private val document = MusicDocument("tree", "song", "folder", "song.mp3")
    private fun preview(title: String) = FilePreview(LocalTrack(document.name, title, listOf("Artist"), "Album", null),
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    @Test
    fun frozenFrameKeepsCoverAndTextAfterLiveRowsAreClearedOrUpdated() {
        val item = FileItem(document)
        val original = preview("Original")
        item.showPreview(original)
        val live = MainUiState(directory = directory, items = listOf(item))
        val frozen = live.freezePreviews()
        assertNotSame(live.items, frozen.items)
        assertNotSame(item, frozen.items.single())
        assertNotSame(item.preview, frozen.items.single().preview)
        assertNotSame(original, frozen.items.single().preview.value)
        item.releaseArtwork()
        assertNull(item.preview.value.artwork)
        assertSame(original.artwork, frozen.items.single().preview.value.artwork)
        item.showPreview(preview("Late"))
        assertEquals(original, frozen.items.single().preview.value)
    }

    @Test
    fun sameUriInAReplacementListDoesNotAcceptTheOldRowsResult() {
        val old = FileItem(document)
        val replacement = FileItem(document)
        val current = MainUiState(items = listOf(replacement))
        assertFalse(current.acceptPreview(old, preview("Old request")))
        assertNull(old.preview.value.track)
        assertNull(replacement.preview.value.track)
        assertTrue(current.acceptPreview(replacement, preview("Current request")))
        assertEquals("Current request", replacement.preview.value.track?.title)
    }

    @Test
    fun outgoingCompositionFreezesLatestPreviewAndCanBecomeLiveAgain() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val item = FileItem(document)
        val live = mutableStateOf(MainUiState(directory = directory, items = listOf(item), storageGranted = true, loading = false))
        val active = mutableStateOf(true)
        var displayed = MainUiState()
        suspend fun settle() {
            Snapshot.sendApplyNotifications()
            withTimeout(5_000) {
                do {
                    yield()
                    if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                    yield()
                } while (recomposer.hasPendingWork || clock.hasAwaiters)
                recomposer.awaitIdle()
                yield()
            }
        }
        try {
            composition.setContent {
                val frame = rememberDirectoryState(directory.uri, active.value, live.value)
                SideEffect { displayed = frame }
            }
            settle()
            // Row-only delivery does not replace MainUiState or trigger a parent snapshot update.
            val original = preview("Arrived before exit")
            item.showPreview(original)
            active.value = false
            // DirectoryShown clears items before the destination URI changes.
            live.value = live.value.copy(items = emptyList(), loading = true)
            settle()
            val frozen = displayed
            assertEquals(original, frozen.items.single().preview.value)
            item.releaseArtwork()
            item.showPreview(preview("Arrived after exit"))
            live.value = live.value.copy(directory = directory.copy(uri = "other-folder"))
            settle()
            assertSame(frozen, displayed)
            assertEquals(original, displayed.items.single().preview.value)

            val restored = FileItem(document)
            active.value = true
            live.value = live.value.copy(directory = directory, items = listOf(restored), loading = false, artworkRevision = 1)
            settle()
            assertSame(live.value, displayed)
            assertSame(restored, displayed.items.single())
            assertFalse(live.value.acceptPreview(item, preview("Detached request")))
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }
}
