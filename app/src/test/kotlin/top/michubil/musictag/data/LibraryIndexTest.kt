package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CancellationException
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameTag
import top.michubil.musictag.data.storage.MusicDocument

class LibraryIndexTest {
    private val searchText = TagSearch.normalizeFields(TagSearch.fields("01.flac", AudioTextMetadata.from(
        title = "七里香", artists = listOf("周杰伦"), album = "七里香",
        albumArtists = listOf("群星"), comment = "Jay", date = "2004-08-03",
    )))

    private fun matches(query: String): Boolean = TagSearch.matches(TagSearch.tokens(query), searchText)

    @Test
    fun artistNamesAreNotResplitOnFilenameSeparators() {
        val metadata = AudioTextMetadata.from(title = "歌", artists = listOf("甲 & 乙", "丙"))
        assertEquals(listOf("甲 & 乙", "丙"), TagSearch.track("song.flac", metadata).artists)
        assertEquals("甲 & 乙 & 丙", metadata.values[RenameTag.ARTISTS])
    }

    @Test
    fun trackKeepsYearAndAlbumArtistsForLibraryGrouping() {
        val metadata = AudioTextMetadata.from(
            title = "歌", artists = listOf("甲"), album = "辑", albumArtists = listOf("乙"), date = "2017-01-02",
        )
        val track = TagSearch.track("song.flac", metadata)
        assertEquals(2017, track.year)
        assertEquals(listOf("乙"), track.albumArtists)
    }

    @Test
    fun matchesFilenameTitleArtistAlbumAndRelatedTags() {
        assertTrue(matches("01.flac"))
        assertTrue(matches("七里香"))
        assertTrue(matches("周杰伦"))
        assertTrue(matches("jay"))
        assertTrue(matches("2004"))
        assertTrue(matches("2004-08-03"))
        assertTrue(matches("群星"))
        assertFalse(matches("稻香"))
    }

    @Test
    fun tokensAreAndedAndIgnoreCaseAndWidth() {
        assertTrue(matches("周杰伦 七里香"))
        assertTrue(matches("JAY"))
        assertTrue(matches("ＪＡＹ"))
        assertFalse(matches("周杰伦 稻香"))
        assertFalse(matches("   "))
    }

    @Test
    fun filterSearchKeepsMatchesAndSortsByName() {
        val jay = entry("b.flac", "七里香", "周杰伦", "七里香")
        val other = entry("a.mp3", "稻香", "周杰伦", "魔杰座")
        val index = LibraryIndex(listOf(jay, other))
        val results = filterSearch(index, "七里香", FileSort.NAME, false)
        assertEquals(listOf("b.flac"), results.map { it.document.name })
        assertEquals(listOf("a.mp3", "b.flac"), filterSearch(index, "周杰伦", FileSort.NAME, false).map { it.document.name })
        assertEquals(listOf("b.flac", "a.mp3"), filterSearch(index, "周杰伦", FileSort.NAME, true).map { it.document.name })
        assertTrue(filterSearch(index, "  ", FileSort.NAME, false).isEmpty())
    }

    @Test
    fun albumTracksReuseIndexedTagsOnlyForTheSameDocumentSnapshot() {
        val song = entry("song.flac", "七里香", "周杰伦", "七里香")
        val index = LibraryIndex(listOf(song))
        val document = index.albums(AlbumSort.TITLE).single().tracks.single()
        assertEquals(song.track, index.track(document))
        listOf(document.copy(treeUri = "another-tree"), document.copy(size = 200),
            document.copy(modified = 200), document.copy(name = "renamed.flac")).forEach {
            assertNull(index.track(it))
        }
    }

    @Test
    fun canceledSearchDoesNotReturnMatches() {
        val index = LibraryIndex(listOf(entry("song.flac", "七里香", "周杰伦", "七里香")))
        assertThrows(CancellationException::class.java) {
            filterSearch(index, "七里香", FileSort.NAME, false) { throw CancellationException() }
        }
    }

    private fun entry(name: String, title: String, artist: String, album: String) = LibraryEntry(
        MusicDocument("tree", "uri:$name", "root", name),
        TagSearch.fields(name, AudioTextMetadata.from(title = title, artists = listOf(artist), album = album)),
        LocalTrack(name, title, listOf(artist), album, null),
    )
}
