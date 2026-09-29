package top.michubil.musictag.data

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.michubil.musictag.data.cache.MusicCache
import top.michubil.musictag.data.storage.SafStorage
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.storage.MusicDocument

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PreviewDeliveryTest {
    private lateinit var library: MusicLibrary
    private lateinit var document: MusicDocument

    @Before
    fun setUp() {
        val app: Application = RuntimeEnvironment.getApplication()
        val storage = SafStorage(app)
        val cache = MusicCache(app)
        library = MusicLibrary(app, storage, cache, AudioFiles(app, storage, cache))
        val bytes = Id3Codec.renderTag(listOf(requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song")))))
        document = MusicDocument("tree", "content://preview/song", "parent", "song.mp3", size = bytes.size.toLong(), modified = 1_000)
        shadowOf(app.contentResolver).registerInputStream(Uri.parse(document.uri), bytes.inputStream())
        runBlocking(Dispatchers.IO) { library.clearBrowseCache(document.treeUri) }
    }

    @Test
    fun readyRunsBeforeCacheWriteAndCompletedPreviewIsThenCached() = runBlocking(Dispatchers.IO) {
        val events = mutableListOf<String>()
        val result = library.preview(document) { ready ->
            assertEquals("Song", ready.track?.title)
            assertNull(library.preview(document, cachedOnly = true).track)
            events += "ready"
        }
        assertEquals("Song", library.preview(document, cachedOnly = true).track?.title)
        events += "cached"
        assertEquals(listOf("ready", "cached"), events)
        assertEquals("Song", result.track?.title)
    }

    @Test
    fun clearingCacheDuringDeliveryRejectsTheInFlightRevision() = runBlocking(Dispatchers.IO) {
        library.preview(document) { ready ->
            assertEquals("Song", ready.track?.title)
            library.clearBrowseCache(document.treeUri)
        }
        assertNull(library.preview(document, cachedOnly = true).track)
    }

    @Test
    fun cancelledDeliveryDoesNotPersistThePreview() = runBlocking(Dispatchers.IO) {
        try {
            library.preview(document) { throw CancellationException("Row left the page") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertNull(library.preview(document, cachedOnly = true).track)
        }
    }
}
