package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameTag
import top.michubil.musictag.data.storage.MusicDocument

class TagSearchTest {
    private val fields = TagSearch.fields("01.flac", AudioTextMetadata.from(
        title = "七里香", artists = listOf("周杰伦"), album = "七里香",
        albumArtists = listOf("群星"), comment = "Jay", date = "2004-08-03",
    ))

    @Test
    fun artistNamesAreNotResplitOnFilenameSeparators() {
        val metadata = AudioTextMetadata.from(title = "歌", artists = listOf("甲 & 乙", "丙"))
        assertEquals(listOf("甲 & 乙", "丙"), TagSearch.track("song.flac", metadata).artists)
        assertEquals("甲 & 乙 & 丙", metadata.values[RenameTag.ARTISTS])
    }

    @Test
    fun matchesFilenameTitleArtistAlbumAndRelatedTags() {
        assertTrue(TagSearch.matches("01.flac", fields))
        assertTrue(TagSearch.matches("七里香", fields))
        assertTrue(TagSearch.matches("周杰伦", fields))
        assertTrue(TagSearch.matches("jay", fields))
        assertTrue(TagSearch.matches("2004", fields))
        assertTrue(TagSearch.matches("2004-08-03", fields))
        assertTrue(TagSearch.matches("群星", fields))
        assertFalse(TagSearch.matches("稻香", fields))
    }

    @Test
    fun tokensAreAndedAndIgnoreCaseAndWidth() {
        assertTrue(TagSearch.matches("周杰伦 七里香", fields))
        assertTrue(TagSearch.matches("JAY", fields))
        assertTrue(TagSearch.matches("ＪＡＹ", fields))
        assertFalse(TagSearch.matches("周杰伦 稻香", fields))
        assertFalse(TagSearch.matches("   ", fields))
    }

    @Test
    fun filterSearchKeepsMatchesAndSortsByName() {
        val jay = entry("b.flac", "七里香", "周杰伦", "七里香")
        val other = entry("a.mp3", "稻香", "周杰伦", "魔杰座")
        val results = filterSearch(listOf(jay, other), "七里香", FileSort.NAME, false)
        assertEquals(listOf("b.flac"), results.map { it.document.name })
        assertEquals(listOf("a.mp3", "b.flac"), filterSearch(listOf(jay, other), "周杰伦", FileSort.NAME, false).map { it.document.name })
    }

    private fun entry(name: String, title: String, artist: String, album: String) = SearchEntry(
        MusicDocument("tree", "uri:$name", "root", name),
        TagSearch.fields(name, AudioTextMetadata.from(title = title, artists = listOf(artist), album = album)),
        LocalTrack(name, title, listOf(artist), album, null),
    )
}
