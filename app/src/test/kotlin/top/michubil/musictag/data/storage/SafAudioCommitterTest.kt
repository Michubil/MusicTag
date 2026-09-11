package top.michubil.musictag.data.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

class SafAudioCommitterTest {
    @TempDir lateinit var temporary: Path
    private val originalBytes = "original audio and tags".toByteArray()
    private val editedBytes = "same audio with edited tags".toByteArray()

    @Test
    fun successfulCommitKeepsNameAndHandlesChangedDocumentIds() {
        val fixture = fixture("success")
        fixture.commit()
        assertArrayEquals(editedBytes, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
        assertFalse(fixture.store.onlyDocument().uri == fixture.original.uri)
    }

    @Test
    fun partialUploadFailureNeverChangesOriginal() {
        val fixture = fixture("partial")
        fixture.store.partialUpload = true
        assertThrows(IOException::class.java) { fixture.commit() }
        assertArrayEquals(originalBytes, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun corruptedUploadIsRejectedBeforeRenamingOriginal() {
        val fixture = fixture("corrupt-upload")
        fixture.store.hook = { event ->
            if (event == "afterWrite") fixture.store.corruptStaged()
        }
        assertThrows(IllegalStateException::class.java) { fixture.commit() }
        assertArrayEquals(originalBytes, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
    }

    @Test
    fun failedPromotionRestoresOriginalWithItsName() {
        val fixture = fixture("promotion")
        fixture.store.hook = { event -> if (event == "beforePromote") throw IOException("rename failed") }
        assertThrows(IOException::class.java) { fixture.commit() }
        assertArrayEquals(originalBytes, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun providerErrorAfterCompletedRenameIsReconciledAsSuccess() {
        val fixture = fixture("late-error")
        fixture.store.hook = { event -> if (event == "afterPromote") throw IOException("provider disconnected") }
        fixture.commit()
        assertArrayEquals(editedBytes, fixture.store.bytes("song.flac"))
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun processDeathAtEachPhaseRecoversWithoutTruncatingOriginal() {
        val phases = listOf("afterCreate", "afterWrite", "afterBackup", "afterPromote", "afterDeleteBackup")
        phases.forEach { phase ->
            val fixture = fixture(phase)
            fixture.store.hook = { event -> if (event == phase) throw ProcessDeath() }
            assertThrows(ProcessDeath::class.java) { fixture.commit() }
            // Discard the committer; recovery must use only the durable journal and provider state.
            fixture.store.hook = {}
            SafAudioCommitter(fixture.store, fixture.journalDirectory).recover("tree")
            val expected = if (phase in listOf("afterPromote", "afterDeleteBackup")) editedBytes else originalBytes
            assertArrayEquals(expected, fixture.store.bytes("song.flac"), phase)
            assertEquals(listOf("song.flac"), fixture.store.names(), phase)
            assertTrue(fixture.committer.pendingTrees().isEmpty(), phase)
        }
    }

    @Test
    fun unavailableProviderKeepsBothCopiesUntilRecoveryCanFinish() {
        val fixture = fixture("offline")
        fixture.store.hook = { event ->
            if (event == "beforePromote" || event == "beforeRestore") throw IOException("offline")
        }
        assertThrows(IOException::class.java) { fixture.commit() }
        assertTrue(fixture.store.names().any { it.startsWith(".musictag-original-") })
        assertTrue(fixture.store.names().any { it.startsWith(".musictag-new-") })
        assertEquals(setOf("tree"), fixture.committer.pendingTrees())
        fixture.store.hook = {}
        fixture.committer.recover("tree")
        assertArrayEquals(originalBytes, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
    }

    @Test
    fun externalChangesDuringUploadArePreservedAndAbortTheEdit() {
        val fixture = fixture("external")
        val external = "changed by another editor".toByteArray()
        fixture.store.hook = { event -> if (event == "afterWrite") fixture.store.changeOriginal(external) }
        assertThrows(IllegalStateException::class.java) { fixture.commit() }
        assertArrayEquals(external, fixture.store.bytes("song.flac"))
        assertEquals(listOf("song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun unexpectedFinalContentsKeepOriginalBackupAndJournal() {
        val fixture = fixture("final-conflict")
        fixture.store.hook = { event -> if (event == "afterPromote") fixture.store.changeOriginal("external edit".toByteArray()) }
        assertThrows(IOException::class.java) { fixture.commit() }
        assertArrayEquals(originalBytes, fixture.store.backupBytes())
        assertEquals(setOf("tree"), fixture.committer.pendingTrees())
        assertThrows(IOException::class.java) { fixture.committer.recover("tree") }
        assertArrayEquals(originalBytes, fixture.store.backupBytes())
    }

    @Test
    fun unsupportedProviderAndUnchangedOutputDoNotCreateTransactions() {
        val fixture = fixture("capabilities")
        assertTrue(canSafelyReplace(fixture.original, fixture.store.root))
        assertFalse(canSafelyReplace(fixture.original.copy(canRename = false), fixture.store.root))
        assertFalse(canSafelyReplace(fixture.original.copy(canDelete = false), fixture.store.root))
        assertFalse(canSafelyReplace(fixture.original, fixture.store.root.copy(canCreate = false)))
        assertThrows(IllegalArgumentException::class.java) {
            fixture.committer.commit(fixture.original.copy(canRename = false), fixture.store.root, digest(originalBytes), fixture.edited)
        }
        fixture.edited.writeBytes(originalBytes)
        fixture.commit()
        assertEquals(listOf("song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun missingOrDuplicateOriginalNamesAreRejectedBeforeCreatingASibling() {
        val fixture = fixture("duplicates")
        fixture.store.add("song.flac", originalBytes)
        assertThrows(IllegalStateException::class.java) { fixture.commit() }
        assertEquals(listOf("song.flac", "song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    @Test
    fun providerNameChangesAreRejectedAndBothTemporaryNamesAreCleanedUp() {
        listOf("Create", "Backup", "Promote").forEach { phase ->
            val fixture = fixture("name-$phase")
            fixture.store.nameChangePhase = phase
            assertThrows(IllegalStateException::class.java) { fixture.commit() }
            assertArrayEquals(originalBytes, fixture.store.bytes("song.flac"), phase)
            assertEquals(listOf("song.flac"), fixture.store.names(), phase)
            assertTrue(fixture.committer.pendingTrees().isEmpty(), phase)
        }
    }

    @Test
    fun interruptedUnexpectedProviderRenamePreservesOriginalAndRecoveryInformation() {
        val fixture = fixture("unexpected-name-death")
        fixture.store.nameChangePhase = "Backup"
        fixture.store.hook = { if (it == "afterBackup") throw ProcessDeath() }
        assertThrows(ProcessDeath::class.java) { fixture.commit() }
        fixture.store.hook = {}
        assertThrows(IllegalStateException::class.java) { fixture.committer.recover("tree") }
        assertArrayEquals(originalBytes, fixture.store.backupBytes())
        assertEquals(setOf("tree"), fixture.committer.pendingTrees())
    }

    @Test
    fun movedOriginalAndAReusedFilenameDoNotPassIdentityValidation() {
        val fixture = fixture("reused-name")
        fixture.store.rename(fixture.original, "moved.flac")
        fixture.store.add("song.flac", originalBytes)
        assertThrows(IllegalStateException::class.java) { fixture.commit() }
        assertEquals(listOf("moved.flac", "song.flac"), fixture.store.names())
        assertTrue(fixture.committer.pendingTrees().isEmpty())
    }

    private fun fixture(name: String): Fixture {
        val directory = temporary.resolve(name).toFile().apply { mkdirs() }
        val store = FakeStore()
        val original = store.add("song.flac", originalBytes)
        val edited = File(directory, "edited.flac").apply { writeBytes(editedBytes) }
        val journalDirectory = File(directory, "journal")
        return Fixture(store, original, edited, journalDirectory, SafAudioCommitter(store, journalDirectory))
    }

    private data class Fixture(
        val store: FakeStore,
        val original: MusicDocument,
        val edited: File,
        val journalDirectory: File,
        val committer: SafAudioCommitter,
    ) {
        fun commit() = committer.commit(original, store.root, digest("original audio and tags".toByteArray()), edited)
    }

    private class ProcessDeath : Error()

    private class FakeStore : DocumentStore {
        private data class Node(val document: MusicDocument, var bytes: ByteArray)
        private val nodes = linkedMapOf<String, Node>()
        private var nextId = 0
        var hook: (String) -> Unit = {}
        var partialUpload = false
        var nameChangePhase: String? = null
        val root = MusicDocument("tree", "root", null, "Music", isDirectory = true, canCreate = true)

        fun add(name: String, bytes: ByteArray): MusicDocument {
            val doc = MusicDocument("tree", "id-${nextId++}", root.uri, name, canRename = true, canDelete = true)
            nodes[doc.uri] = Node(doc, bytes.copyOf())
            return doc
        }

        override fun children(directory: MusicDocument) = nodes.values.map { it.document }
        override fun read(document: MusicDocument): InputStream = ByteArrayInputStream(nodes.getValue(document.uri).bytes)
        override fun create(directory: MusicDocument, name: String, mimeType: String): MusicDocument =
            add(if (nameChangePhase == "Create") "$name (1)" else name, byteArrayOf()).also { hook("afterCreate") }

        override fun write(document: MusicDocument, source: InputStream) {
            val bytes = source.readBytes()
            nodes.getValue(document.uri).bytes = if (partialUpload) bytes.copyOf(bytes.size / 2) else bytes
            if (partialUpload) throw IOException("disk full")
            hook("afterWrite")
        }

        override fun rename(document: MusicDocument, name: String): MusicDocument {
            val phase = when {
                name.startsWith(".musictag-original-") -> "Backup"
                document.name.startsWith(".musictag-new-") -> "Promote"
                else -> "Restore"
            }
            hook("before$phase")
            check(nodes.values.none { it.document.name == name }) { "name collision" }
            val node = nodes.remove(document.uri) ?: error("stale URI")
            return add(if (nameChangePhase == phase) "$name (1)" else name, node.bytes).also { hook("after$phase") }
        }

        override fun delete(document: MusicDocument) {
            check(nodes.remove(document.uri) != null)
            if (document.name.startsWith(".musictag-original-")) hook("afterDeleteBackup")
        }

        fun names() = nodes.values.map { it.document.name }.sorted()
        fun bytes(name: String) = nodes.values.single { it.document.name == name }.bytes
        fun backupBytes() = nodes.values.single { it.document.name.startsWith(".musictag-original-") }.bytes
        fun onlyDocument() = nodes.values.single().document
        fun corruptStaged() {
            nodes.values.single { it.document.name.startsWith(".musictag-new-") }.bytes = "broken".toByteArray()
        }
        fun changeOriginal(bytes: ByteArray) {
            nodes.values.single { it.document.name == "song.flac" }.bytes = bytes
        }
    }

    companion object {
        private fun digest(bytes: ByteArray) = documentDigest(ByteArrayInputStream(bytes))
    }
}
