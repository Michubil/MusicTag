package top.michubil.musictag.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.TagEditorContent
import top.michubil.musictag.data.edit.TagEditInputs
import top.michubil.musictag.data.edit.TagEditSource
import top.michubil.musictag.data.edit.TagValues
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.model.RemoteValue
import top.michubil.musictag.data.storage.MusicDocument

class TagEditorSessionTest {
    private val source = TagEditSource(MusicDocument("tree", "song", "parent", "song.mp3"), "original-digest",
        TagValues(mapOf(MetadataField.TITLE to "Original")))
    private val content = TagEditorContent(TagEditInputs(listOf(source), listOf("unreadable.flac")), null)

    private class Editor(scope: CoroutineScope, load: suspend () -> TagEditorContent) {
        var state = TagEditorState()
        val messages = mutableListOf<String>()
        val session = TagEditorSession(scope, { _, _, _ -> load() }, { error("No cover requested") },
            { state }, { state = it }, { messages += it })
    }

    @Test
    fun closedEditorRejectsLateResultsEvenWhenTheReaderIgnoresCancellation() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val editor = Editor(this) {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            content
        }
        editor.session.load(listOf(source.document), false, AudioFilters())
        started.await()
        assertTrue(editor.state.loading)
        editor.session.close()
        release.complete(Unit)
        yield()
        assertEquals(TagEditorState(), editor.state)
        assertNull(editor.session.prepareSave(Mp3TagVersion.V24))
        assertTrue(editor.messages.isEmpty())
    }

    @Test
    fun reloadingCannotBeOverwrittenByAnOlderRead() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val editor = Editor(this) {
            if (++calls == 1) {
                withContext(NonCancellable) { release.await() }
                content.copy(inputs = TagEditInputs(listOf(source.copy(document = source.document.copy(name = "old.mp3"))), emptyList()))
            } else content
        }
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        assertEquals("song.mp3", editor.state.fileName)
        release.complete(Unit)
        yield()
        assertEquals("song.mp3", editor.state.fileName)
    }

    @Test
    fun saveRequestSurvivesLeavingAndReopeningTheEditor() = runBlocking {
        val editor = Editor(this) { content }
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        editor.session.changeDraft { it.copy(text = it.text + (MetadataField.TITLE to "Saved"), changed = setOf(MetadataField.TITLE)) }
        val request = requireNotNull(editor.session.prepareSave(Mp3TagVersion.V23))
        val finish = CompletableDeferred<Unit>()
        var savedTitle: RemoteValue<String>? = null
        val operation = launch {
            finish.await()
            savedTitle = request.mutation.metadata.title
        }
        editor.session.close()
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        editor.session.changeDraft { it.copy(text = it.text + (MetadataField.TITLE to "Later"), changed = setOf(MetadataField.TITLE)) }
        finish.complete(Unit)
        operation.join()
        assertEquals(RemoteValue.Available("Saved"), savedTitle)
        assertEquals(listOf(source), request.sources)
        assertEquals(Mp3TagVersion.V23, request.mutation.options.mp3TagVersion)
        assertEquals(1, request.skipped)
    }

    @Test
    fun readFailureCanBeRetriedAndInvalidDraftCannotBeSaved() = runBlocking {
        var fail = true
        val editor = Editor(this) { if (fail) error("Cannot read") else content }
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        assertEquals("Cannot read", editor.state.error)
        assertFalse(editor.state.loading)
        fail = false
        editor.session.load(listOf(source.document), false, AudioFilters())
        yield()
        assertNull(editor.state.error)
        editor.session.changeDraft { it.copy(text = it.text + (MetadataField.DATE to "2026-02-30"), changed = setOf(MetadataField.DATE)) }
        assertNotNull(editor.state.validation)
        assertNull(editor.session.prepareSave(Mp3TagVersion.V24))
        editor.session.changeDraft { it.copy(text = it.text + (MetadataField.DATE to "2026-02-28")) }
        assertNotNull(editor.session.prepareSave(Mp3TagVersion.V24))
    }

    @Test
    fun closingWhileCoverReadFailsCannotPublishAnOldNotification() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var state = TagEditorState()
        val messages = mutableListOf<String>()
        val session = TagEditorSession(this, { _, _, _ -> content }, {
            withContext(NonCancellable) { release.await(); error("Late cover error") }
        }, { state }, { state = it }, { messages += it })
        session.load(listOf(source.document), false, AudioFilters())
        yield()
        session.loadCover("cover")
        yield()
        session.close()
        release.complete(Unit)
        yield()
        assertEquals(TagEditorState(), state)
        assertTrue(messages.isEmpty())
    }
}
