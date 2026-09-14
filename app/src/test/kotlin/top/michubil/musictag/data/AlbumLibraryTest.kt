package top.michubil.musictag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun groupsByAlbumNameRegardlessOfArtistsAndYear() {
        val groups = AlbumLibrary.group(
            listOf(
                entry("a.flac", "Eclipse", "甲", 2020),
                entry("b.flac", " eclipse ", "乙", 2020, "专辑艺术家乙"),
                entry("c.flac", "ECLIPSE", "丙", 2021, "专辑艺术家丙"),
                entry("d.flac", "Other", "甲", 2020),
            ),
        )
        assertEquals(2, groups.size)
        val eclipse = groups.single { it.title == "Eclipse" }
        assertEquals(listOf("a.flac", "b.flac", "c.flac"), eclipse.tracks.map { it.name })
        assertEquals(2020, eclipse.year)
        assertEquals(null, eclipse.artist)
        assertEquals(1, groups.single { it.title == "Other" }.tracks.size)
    }

    @Test
    fun missingAlbumArtistsDoNotSplitAnAlbumByPerformers() {
        val album = AlbumLibrary.group(listOf(
            entry("a.flac", "合集", "甲", null),
            entry("b.flac", "合集", "乙", null, " "),
        )).single()
        assertEquals(2, album.tracks.size)
        assertEquals(null, album.artist)
    }

    @Test
    fun missingAlbumNamesShareOneUnknownAlbum() {
        val album = AlbumLibrary.group(listOf(
            entry("a.flac", null, "甲", null),
            entry("b.flac", "", "乙", null, "专辑艺术家乙"),
            entry("c.flac", "  ", "丙", null),
            LibraryEntry(MusicDocument("tree", "unreadable", "folder", "d.flac"), emptyList(), null),
        )).single()
        assertEquals("未知专辑", album.title)
        assertTrue(album.key.isNotBlank())
        assertEquals(4, album.tracks.size)
    }

    @Test
    fun aSharedAlbumArtistRemainsVisibleForDifferentPerformers() {
        val album = AlbumLibrary.group(listOf(
            entry("a.flac", "合集", "甲", null, " Various Artists "),
            entry("b.flac", "合集", "乙", null, "various artists"),
        )).single()
        assertEquals("Various Artists", album.artist)
        assertEquals(2, album.tracks.size)
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
