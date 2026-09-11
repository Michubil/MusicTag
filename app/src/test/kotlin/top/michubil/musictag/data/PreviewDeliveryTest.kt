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
import top.michubil.musictag.data.flac.SafeFlacEditor
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.id3.SafeMp3Editor
import top.michubil.musictag.data.network.MetadataSourcesClient
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.wav.SafeWavEditor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PreviewDeliveryTest {
    private lateinit var repository: MusicRepository
    private lateinit var document: MusicDocument

    @Before
    fun setUp() {
        val app: Application = RuntimeEnvironment.getApplication()
        repository = MusicRepository(app, MetadataSourcesClient(), SafeFlacEditor(), SafeMp3Editor(), SafeWavEditor())
        val bytes = Id3Codec.renderTag(listOf(requireNotNull(Id3Codec.textFrame("TIT2", listOf("Song")))))
        document = MusicDocument("tree", "content://preview/song", "parent", "song.mp3", size = bytes.size.toLong(), modified = 1_000)
        shadowOf(app.contentResolver).registerInputStream(Uri.parse(document.uri), bytes.inputStream())
        runBlocking(Dispatchers.IO) { repository.clearBrowseCache(document.treeUri) }
    }

    @Test
    fun readyRunsBeforeCacheWriteAndCompletedPreviewIsThenCached() = runBlocking(Dispatchers.IO) {
        val events = mutableListOf<String>()
        val result = repository.preview(document) { ready ->
            assertEquals("Song", ready.track?.title)
            assertNull(repository.preview(document, cachedOnly = true).track)
            events += "ready"
        }
        assertEquals("Song", repository.preview(document, cachedOnly = true).track?.title)
        events += "cached"
        assertEquals(listOf("ready", "cached"), events)
        assertEquals("Song", result.track?.title)
    }

    @Test
    fun clearingCacheDuringDeliveryRejectsTheInFlightRevision() = runBlocking(Dispatchers.IO) {
        repository.preview(document) { ready ->
            assertEquals("Song", ready.track?.title)
            repository.clearBrowseCache(document.treeUri)
        }
        assertNull(repository.preview(document, cachedOnly = true).track)
    }

    @Test
    fun cancelledDeliveryDoesNotPersistThePreview() = runBlocking(Dispatchers.IO) {
        try {
            repository.preview(document) { throw CancellationException("Row left the page") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertNull(repository.preview(document, cachedOnly = true).track)
        }
    }
}
