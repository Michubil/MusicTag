package top.michubil.musictag.ui.navigation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BrowserDirectoryEffectTest {
    @Test
    fun reusedFolderEntryLoadsEachNewUriAndReloadsAfterReturningFromSettings() = runBlocking {
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
        val root = "content://provider/tree/music/document/music"
        val album = "content://provider/tree/music/document/opaque-album"
        val album1 = "content://provider/tree/music/document/opaque-album1"
        val entryId = mutableStateOf("root-entry")
        val route = mutableStateOf(Routes.Files)
        val rootUri = mutableStateOf<String?>(null)
        val folderUri = mutableStateOf<String?>(null)
        val unrelatedProgress = mutableStateOf(0)
        val reads = mutableListOf<String>()

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
                unrelatedProgress.value
                BrowserDirectoryEffect(entryId.value, browserDirectoryUri(route.value, rootUri.value, folderUri.value)) {
                    reads += it
                }
            }
            settle()
            assertEquals(emptyList<String>(), reads)
            rootUri.value = root
            settle()
            assertEquals(listOf(root), reads)

            entryId.value = "folder-entry"
            route.value = Routes.Folder
            folderUri.value = album
            settle()
            assertEquals(listOf(root, album), reads)

            // Match launchSingleTop: the destination and entry ID stay the same at the next level.
            folderUri.value = album1
            settle()
            assertEquals(listOf(root, album, album1), reads)
            unrelatedProgress.value++
            settle()
            assertEquals(listOf(root, album, album1), reads)

            route.value = Routes.Settings
            settle()
            assertEquals(listOf(root, album, album1), reads)
            route.value = Routes.Folder
            settle()
            assertEquals(listOf(root, album, album1, album1), reads)
            folderUri.value = album
            settle()
            assertEquals(listOf(root, album, album1, album1, album), reads)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }
}
