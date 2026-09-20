package top.michubil.musictag.data.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocumentSelectionTest {
    private val root = doc("root", folder = true)
    private val child = doc("child", folder = true)
    private val direct = doc("音乐.MP3")
    private val nested = doc("album.flac")
    private val other = doc("cover.jpg")
    private fun children(document: MusicDocument) = when (document.uri) {
        root.uri -> listOf(child, direct, other)
        child.uri -> listOf(nested, root) // A provider cycle must not loop forever.
        else -> emptyList()
    }

    @Test
    fun nonRecursiveSelectionIncludesOnlyDirectAudio() {
        assertEquals(listOf(direct), expandDocuments(listOf(root), false, ::children))
    }

    @Test
    fun recursiveSelectionDeduplicatesOverlappingChoicesAndCycles() {
        assertEquals(setOf(direct, nested), expandDocuments(listOf(root, child, nested), true, ::children).toSet())
    }

    @Test
    fun selectionUsesUriIdentityEvenWhenDisplayNamesMatch() {
        val second = direct.copy(uri = "different-id")
        assertEquals(listOf(direct, second), expandDocuments(listOf(direct, second), false) { emptyList() })
    }

    private fun doc(name: String, folder: Boolean = false) = MusicDocument("tree", name, "root", name, isDirectory = folder)
}
