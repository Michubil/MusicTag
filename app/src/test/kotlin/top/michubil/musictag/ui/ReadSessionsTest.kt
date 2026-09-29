package top.michubil.musictag.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.match.CandidateSearch
import top.michubil.musictag.data.match.MatchOutcome
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameInputs
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.rename.RenameSource
import top.michubil.musictag.data.storage.MusicDocument

class ReadSessionsTest {
    private val old = MusicDocument("tree", "old", "parent", "old.mp3", canRename = true)
    private val fresh = old.copy(uri = "fresh", name = "fresh.mp3")
    private val search = CandidateSearch(emptyList(), MatchOutcome.None("No match"))

    @Test
    fun supersededCandidateSuccessAndFailureCannotReplaceTheNewSession() = runBlocking {
        withTimeout(5_000) {
            for (fail in listOf(false, true)) {
                var state = CandidateState()
                val messages = mutableListOf<String>()
                coroutineScope {
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val session = CandidateSession(this, { selection, _, _ -> selection }, { file, _ ->
                        if (file == old) withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                            if (fail) error("Old search failed")
                        }
                        search
                    }, { state = it }, {}, { messages += it })
                    session.load(listOf(old), false, AudioFilters(), ScrapeOptions())
                    started.await()
                    session.load(listOf(fresh), false, AudioFilters(), ScrapeOptions())
                    yield()
                    release.complete(Unit)
                }
                assertEquals(fresh.uri, state.session?.uri)
                assertFalse(state.loading)
                assertNull(state.error)
                assertTrue(messages.isEmpty())
            }
        }
    }

    @Test
    fun leavingDuringExpansionDoesNotOpenCandidatesOrReleaseAWrite() = runBlocking {
        withTimeout(5_000) {
            var state = MainUiState(writing = true)
            var opened = false
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val session = CandidateSession(this, { selection, _, _ ->
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        selection
                    }
                }, { _, _ -> error("Cancelled expansion must not search") },
                    { state = state.copy(candidateSearch = it) }, { opened = true }, { error(it) })
                session.load(listOf(old), false, AudioFilters(), ScrapeOptions())
                started.await()
                session.close()
                release.complete(Unit)
            }
            assertFalse(opened)
            assertEquals(CandidateState(), state.candidateSearch)
            assertTrue(state.writing)
            assertTrue(state.busy)
        }
    }

    @Test
    fun supersededRenameReadsCannotReplaceFreshInputsOrErrors() = runBlocking {
        withTimeout(5_000) {
            for (fail in listOf(false, true)) {
                var state = RenameState()
                coroutineScope {
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val session = RenameSession(this, { selection, _, _, _ ->
                        val file = selection.single()
                        if (file == old) withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                            if (fail) error("Old read failed")
                        }
                        inputs(file)
                    }, { state }, { state = it })
                    session.load(listOf(old), false, AudioFilters())
                    started.await()
                    session.load(listOf(fresh), false, AudioFilters())
                    yield()
                    release.complete(Unit)
                }
                assertEquals(fresh, state.entries.single().document)
                assertFalse(state.loading)
                assertNull(state.error)
            }
        }
    }

    @Test
    fun closingRenameDropsReadStateButPreservesTheRuleAndCapturedCommit() = runBlocking {
        var state = RenameState()
        val session = RenameSession(this, { selection, _, _, _ -> inputs(selection.single()) }, { state }, { state = it })
        session.setPreset(RenamePreset.CUSTOM)
        session.setPattern("@1")
        session.load(listOf(fresh), false, AudioFilters())
        yield()
        val captured = state.entries
        session.close()
        assertEquals("@1", state.pattern)
        assertEquals(RenamePreset.CUSTOM, state.preset)
        assertTrue(state.entries.isEmpty())
        assertFalse(state.canApply)
        assertEquals("New title.mp3", captured.single().newName)
    }

    private fun inputs(file: MusicDocument) = RenameInputs(
        listOf(RenameSource(file, AudioTextMetadata.from(title = "New title", artists = listOf("Artist")))),
        mapOf("parent" to listOf(file)),
    )
}
