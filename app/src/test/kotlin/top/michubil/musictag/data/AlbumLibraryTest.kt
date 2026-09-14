package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument

class AlbumLibraryTest {
    private fun entry(name: String, album: String?, artist: String?, year: Int?, albumArtist: String? = null) = LibraryEntry(
        document = MusicDocument("tree", name, "folder", name, size = 1, modified = 1),
        fields = listOf(name),
        track = LocalTrack(
            fileName = name,
            title = name,
            artists = listOfNotNull(artist),
            album = album,
            durationMs = null,
            year = year,
            albumArtists = listOfNotNull(albumArtist),
        ),
    )

    @Test
    fun groupsByAlbumAndArtistAndKeepsUnknownTitlesTogetherByArtist() {
        val groups = AlbumLibrary.group(
            listOf(
                entry("a.flac", "Eclipse", "甲", 2020),
                entry("b.flac", "Eclipse", "甲", 2020),
                entry("c.flac", "Eclipse", "乙", 2021),
                entry("d.flac", null, "丙", null),
            ),
        )
        assertEquals(3, groups.size)
        val eclipseJia = groups.single { it.title == "Eclipse" && it.artist == "甲" }
        assertEquals(2, eclipseJia.tracks.size)
        assertEquals(2020, eclipseJia.year)
        assertEquals("未知专辑", groups.single { it.artist == "丙" }.title)
    }

    @Test
    fun summaryOmitsMissingYearAndArtist() {
        assertEquals("1首", AlbumLibrary.summary(1, null, null))
        assertEquals("2首 2017 Animenz", AlbumLibrary.summary(2, 2017, "Animenz"))
        assertEquals("1首 鸭鸭柚__", AlbumLibrary.summary(1, null, "鸭鸭柚__"))
    }

    @Test
    fun titleSortUsesLettersThenNameAndYearSortPutsNewerFirst() {
        val grouped = AlbumLibrary.group(
            listOf(
                entry("z.flac", "Zen", "A", 2010),
                entry("a.flac", "Absolute", "A", 2020),
                entry("m.flac", "Middle", "A", null),
            ),
        )
        assertEquals(listOf("Absolute", "Middle", "Zen"), AlbumLibrary.sort(grouped, AlbumSort.TITLE).map { it.title })
        assertEquals(listOf("Absolute", "Zen", "Middle"), AlbumLibrary.sort(grouped, AlbumSort.YEAR).map { it.title })
    }

    @Test
    fun countSortPutsLargerAlbumsFirst() {
        val grouped = AlbumLibrary.group(
            listOf(
                entry("a.flac", "One", "A", 1),
                entry("b.flac", "Two", "A", 1),
                entry("c.flac", "Two", "A", 1),
            ),
        )
        assertEquals(listOf("Two", "One"), AlbumLibrary.sort(grouped, AlbumSort.COUNT).map { it.title })
    }

    @Test
    fun latinTitlesMapToIndexLetters() {
        assertEquals("A", AlbumLibrary.indexLetter("Absolute Greatest"))
        assertEquals("Z", AlbumLibrary.indexLetter("zen"))
        assertEquals("#", AlbumLibrary.indexLetter("99"))
        assertEquals("#", AlbumLibrary.indexLetter(""))
    }
}
