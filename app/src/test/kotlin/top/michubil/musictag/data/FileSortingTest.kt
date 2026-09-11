package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.sortDocuments

class FileSortingTest {
    @Test
    fun foldersStayFirstInBothDirections() {
        val files = listOf(document("z.flac"), document("Album", folder = true), document("a.mp3"))
        assertEquals(listOf("Album", "a.mp3", "z.flac"), sortDocuments(files, FileSort.NAME, false).map { it.name })
        assertEquals(listOf("Album", "z.flac", "a.mp3"), sortDocuments(files, FileSort.NAME, true).map { it.name })
    }

    @Test
    fun sortUsesProviderMetadataAndUnknownTimesRemainUnknown() {
        val files = listOf(document("second.flac", modified = 100), document("first.mp3", modified = 200), document("unknown.wav"))
        assertEquals(listOf("second.flac", "first.mp3", "unknown.wav"), sortDocuments(files, FileSort.TYPE, false).map { it.name })
        assertEquals(listOf("first.mp3", "second.flac", "unknown.wav"), sortDocuments(files, FileSort.MODIFIED, true).map { it.name })
        assertEquals(listOf("unknown.wav", "second.flac", "first.mp3"), sortDocuments(files, FileSort.MODIFIED, false).map { it.name })
    }

    private fun document(name: String, folder: Boolean = false, modified: Long? = null) =
        MusicDocument("tree", "uri:$name", "root", name, isDirectory = folder, modified = modified)
}
