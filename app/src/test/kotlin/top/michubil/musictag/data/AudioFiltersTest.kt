package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.expandDocuments

class AudioFiltersTest {
    @Test
    fun durationBoundaryKeepsEqualLongerAndUnknownDurations() {
        AudioFilters.minimumSecondsOptions.filter { it > 0 }.forEach { seconds ->
            val filter = AudioFilters(seconds)
            assertFalse(filter.allowsDuration(seconds * 1000L - 1))
            assertTrue(filter.allowsDuration(seconds * 1000L))
            assertTrue(filter.allowsDuration(seconds * 1000L + 1))
            assertTrue(filter.allowsDuration(null))
        }
        assertTrue(AudioFilters().allowsDuration(1))
        assertThrows(IllegalArgumentException::class.java) { AudioFilters(15) }
    }

    @Test
    fun directoryPathsNormalizeAndDoNotExcludeSimilarSiblingNames() {
        val paths = AudioFilters.parsePaths(" /播客/缓存/ \nAlbums\\Live\n\n albums/live ")
        assertEquals(listOf("播客/缓存", "Albums/Live"), paths)
        val filters = AudioFilters(excludedPaths = paths)
        listOf("播客/缓存", "播客/缓存/节目.mp3", "albums/live/a.flac").forEach { assertTrue(filters.excludes(it)) }
        listOf("播客/缓存库/a.mp3", "Albums/Live2", "播客", "").forEach { assertFalse(filters.excludes(it)) }
        listOf("../秘密", "Music/../私人", "a//b", "C:\\Music", "content://provider/tree/1").forEach {
            assertThrows(IllegalArgumentException::class.java) { AudioFilters.parsePaths(it) }
        }
    }

    @Test
    fun recursiveExpansionNeverEntersExcludedSubtrees() {
        fun folder(path: String) = MusicDocument("tree", path, "root", path, isDirectory = true, relativePath = path)
        val root = folder("")
        val blocked = folder("缓存")
        val allowed = folder("专辑")
        val song = MusicDocument("tree", "song", allowed.uri, "song.flac", relativePath = "专辑/song.flac")
        val filter = AudioFilters(excludedPaths = listOf("缓存"))
        val files = expandDocuments(listOf(root), true) { directory ->
            when (directory.uri) {
                root.uri -> listOf(blocked, allowed)
                allowed.uri -> listOf(song)
                else -> error("Excluded directory must not be read")
            }.filterNot(filter::excludes)
        }
        assertEquals(listOf(song), files)
    }
}
