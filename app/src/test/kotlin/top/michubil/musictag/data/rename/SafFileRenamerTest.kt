package top.michubil.musictag.data.rename

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.DocumentStore
import top.michubil.musictag.data.storage.MusicDocument
import java.io.InputStream
import java.io.IOException

class SafFileRenamerTest {
    private val parent = MusicDocument("tree", "parent", null, "音乐", isDirectory = true)
    private val original = MusicDocument("tree", "old-id", parent.uri, "old.FLAC", size = 120, modified = 10, canRename = true)
    private val entry = RenameEntry(original, "new.FLAC")

    @Test
    fun usesReturnedUriAndNeverOpensOrRewritesAudio() {
        val store = Store(listOf(original))
        val renamed = SafFileRenamer(store).rename(entry, parent)
        assertEquals("new-id", renamed.uri)
        assertEquals("new.FLAC", renamed.name)
        assertEquals(1, store.renameCount)
        assertEquals(listOf(renamed), store.files)
    }

    @Test
    fun racesAndChangedOriginalsAreRejectedBeforeMutation() {
        val scenarios = listOf(
            emptyList(), listOf(original.copy(size = 121)), listOf(original.copy(modified = 11)),
            listOf(original.copy(canRename = false)), listOf(original.copy(name = "moved.FLAC")),
            listOf(original, original.copy(uri = "other", name = "NEW.flac")),
        )
        scenarios.forEach { files ->
            val store = Store(files)
            assertThrows(RuntimeException::class.java) { SafFileRenamer(store).rename(entry, parent) }
            assertEquals(0, store.renameCount)
        }
    }

    @Test
    fun mismatchedParentAndNoOpsCannotRenameAnything() {
        val store = Store(listOf(original))
        val renamer = SafFileRenamer(store)
        assertThrows(IllegalArgumentException::class.java) { renamer.rename(entry, parent.copy(treeUri = "other")) }
        assertThrows(IllegalArgumentException::class.java) { renamer.rename(entry.copy(newName = original.name), parent) }
        assertThrows(IllegalArgumentException::class.java) { renamer.rename(entry.copy(error = "skip"), parent) }
        assertEquals(0, store.renameCount)
    }

    @Test
    fun providerRenameFailureLeavesSourceUntouched() {
        val store = Store(listOf(original)).apply { fail = true }
        assertThrows(IOException::class.java) { SafFileRenamer(store).rename(entry, parent) }
        assertEquals(listOf(original), store.files)
    }

    @Test
    fun unexpectedProviderNameIsReportedWithoutDestructiveRollback() {
        val store = Store(listOf(original)).apply { returnWrongName = true }
        assertThrows(IllegalStateException::class.java) { SafFileRenamer(store).rename(entry, parent) }
        assertEquals(1, store.renameCount)
        assertEquals("provider-name.FLAC", store.files.single().name)
    }

    private class Store(var files: List<MusicDocument>) : DocumentStore {
        var renameCount = 0
        var fail = false
        var returnWrongName = false
        override fun children(directory: MusicDocument) = files
        override fun rename(document: MusicDocument, name: String): MusicDocument {
            renameCount++
            if (fail) throw IOException("provider offline")
            val renamed = document.copy(uri = "new-id", name = if (returnWrongName) "provider-name.FLAC" else name)
            files = files.map { if (it.uri == document.uri) renamed else it }
            return renamed
        }
        override fun read(document: MusicDocument): InputStream = error("Rename must not read audio")
        override fun create(directory: MusicDocument, name: String, mimeType: String): MusicDocument = error("Rename must not create files")
        override fun write(document: MusicDocument, source: InputStream) = error("Rename must not write audio")
        override fun delete(document: MusicDocument) = error("Rename must not delete files")
    }
}
