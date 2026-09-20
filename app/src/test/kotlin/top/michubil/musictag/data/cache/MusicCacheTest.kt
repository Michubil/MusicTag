package top.michubil.musictag.data.cache

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.AlbumSort
import top.michubil.musictag.data.FileSort
import top.michubil.musictag.data.LibraryEntry
import top.michubil.musictag.data.LibraryIndex
import top.michubil.musictag.data.filterSearch
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MusicCacheTest {
    private lateinit var app: Application
    private lateinit var database: MusicCacheDatabase
    private lateinit var cache: MusicCache
    private val directory = MusicDocument("tree", "folder", null, "专辑", isDirectory = true, relativePath = "专辑")
    private fun song(index: Int) = MusicDocument("tree", "song-$index", "folder", "$index.flac",
        size = 100, modified = 1000, relativePath = "专辑/$index.flac")
    private fun libraryEntry(document: MusicDocument) = LibraryEntry(document, listOf(document.name, "ＴＥＳＴ", "制作人甲"),
        LocalTrack(document.name, "歌曲", listOf("歌手甲", "歌手乙"), "专辑", null, 2024, listOf("专辑艺术家")))

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(app, MusicCacheDatabase::class.java).build()
        cache = MusicCache(app, database)
    }

    @After
    fun tearDown() { database.close() }

    private inline fun <T> withDatabase(database: MusicCacheDatabase, block: (MusicCacheDatabase) -> T): T =
        try { block(database) } finally { database.close() }

    @Test
    fun librarySurvivesReopenWithAlbumFieldsAndSearchTextBeyondTheVisibleTrack() = runBlocking(Dispatchers.IO) {
        val temporaryDirectory = Files.createTempDirectory("music-library-cache-").toFile()
        val path = temporaryDirectory.resolve("cache.db")
        val entries = (1..497).map { libraryEntry(song(it)) }
        try {
            withDatabase(Room.databaseBuilder(app, MusicCacheDatabase::class.java, path.absolutePath).build()) { disk ->
                val first = MusicCache(app, disk)
                first.storeLibrary(directory, AudioFilters(), entries, first.revision())
            }
            withDatabase(Room.databaseBuilder(app, MusicCacheDatabase::class.java, path.absolutePath).build()) { disk ->
                val reopened = MusicCache(app, disk)
                val restored = requireNotNull(reopened.library(directory, AudioFilters()))
                assertEquals(entries.map { it.document }, restored.map { it.document })
                assertEquals(entries.map { it.track }, restored.map { it.track })
                val index = LibraryIndex(restored, isCached = true)
                val album = index.albums(AlbumSort.TITLE).single()
                assertEquals(2024, album.year)
                assertEquals("专辑艺术家", album.artist)
                assertEquals(497, album.tracks.size)
                assertEquals(497, filterSearch(index, "test 制作人甲", FileSort.NAME, false).size)
                val known = reopened.libraryEntries(entries.map { it.document })
                assertEquals(entries.map { it.document }.toSet(), known.keys)
            }
        } finally {
            temporaryDirectory.listFiles().orEmpty().forEach { it.delete() }
            temporaryDirectory.delete()
        }
    }

    @Test
    fun libraryChecksFreshStatsAndFiltersAndKeepsFreshDocumentCapabilities() = runBlocking(Dispatchers.IO) {
        val entries = (1..6).map { libraryEntry(song(it)) }
        cache.storeLibrary(directory, AudioFilters(), entries, cache.revision())
        assertNull(cache.library(directory.copy(uri = "other-root"), AudioFilters()))
        assertNull(cache.library(directory, AudioFilters(20)))
        assertNull(cache.library(directory, AudioFilters(excludedPaths = listOf("专辑"))))
        val changed = listOf(song(1).copy(size = 101), song(2).copy(modified = 1001), song(3).copy(name = "renamed.flac"),
            song(4).copy(treeUri = "other-tree"), song(5).copy(modified = null), song(6).copy(uri = "new-uri"))
        assertTrue(cache.libraryEntries(changed).isEmpty())
        val relocated = song(1).copy(parentUri = "new-parent", relativePath = "另一个专辑/1.flac", canRename = true)
        val reused = requireNotNull(cache.libraryEntries(listOf(relocated))[relocated])
        assertEquals(relocated, reused.document)
        assertEquals(entries.first().track, reused.track)
    }

    @Test
    fun libraryInvalidationRejectsStaleWritesAndKeepsUnaffectedMetadata() = runBlocking(Dispatchers.IO) {
        val entries = (1..3).map { libraryEntry(song(it)) }
        val oldRevision = cache.revision()
        cache.storeLibrary(directory, AudioFilters(), entries, oldRevision)
        cache.invalidate(song(1))
        cache.storeLibrary(directory, AudioFilters(), entries, oldRevision)
        assertNull(cache.library(directory, AudioFilters()))
        assertEquals(setOf(song(2), song(3)), cache.libraryEntries(entries.map { it.document }).keys)
        cache.storeLibrary(directory, AudioFilters(), entries.drop(1), cache.revision())
        cache.retain(directory.treeUri, setOf(directory.uri, song(2).uri))
        assertNull(cache.library(directory, AudioFilters()))
        assertEquals(setOf(song(2)), cache.libraryEntries(entries.map { it.document }).keys)
        cache.clearTree(directory.treeUri)
        assertTrue(cache.libraryEntries(entries.map { it.document }).isEmpty())
    }

    @Test
    fun invalidationDuringTreeWalkCannotPruneNewRowsOrAuthorizeAStaleSnapshotWrite() = runBlocking(Dispatchers.IO) {
        val entries = (1..2).map { libraryEntry(song(it)) }
        cache.storeLibrary(directory, AudioFilters(), entries, cache.revision())
        val scanRevision = cache.revision()
        cache.invalidate(song(1))
        val updated = listOf(libraryEntry(song(1).copy(size = 200)), entries[1], libraryEntry(song(3)))
        cache.storeLibrary(directory, AudioFilters(), updated, cache.revision())
        assertNull(cache.retain(directory.treeUri, setOf(directory.uri, song(1).uri, song(2).uri), scanRevision))
        cache.storeLibrary(directory, AudioFilters(), entries, scanRevision)
        assertEquals(updated.map { it.document }, cache.library(directory, AudioFilters())?.map { it.document })
    }

    @Test
    fun incompleteAndCorruptLibraryRowsAreRetriedWithoutDisablingOtherCacheEntries() = runBlocking(Dispatchers.IO) {
        val valid = libraryEntry(song(1))
        val unreliable = libraryEntry(song(2).copy(modified = null))
        val failed = LibraryEntry(song(3), listOf("3.flac"), null)
        cache.storeLibrary(directory, AudioFilters(), listOf(valid, unreliable, failed), cache.revision())
        assertNull(cache.library(directory, AudioFilters()))
        assertEquals(setOf(song(1)), cache.libraryEntries(listOf(valid.document, unreliable.document, failed.document)).keys)
        cache.storeLibrary(directory, AudioFilters(), listOf(valid, libraryEntry(song(2))), cache.revision())
        val corrupt = database.cache().libraryEntries(directory.treeUri).first().copy(track = "broken JSON")
        database.cache().putLibraryEntries(listOf(corrupt))
        assertNull(cache.library(directory, AudioFilters()))
        assertEquals(setOf(song(2)), cache.libraryEntries(listOf(song(1), song(2))).keys)
        cache.storeDurations(mapOf(song(1) to 30_000L), cache.revision())
        assertEquals(mapOf(song(1) to 30_000L), cache.durations(listOf(song(1))))
        cache.storeLibrary(directory, AudioFilters(), emptyList(), cache.revision())
        assertEquals(emptyList<LibraryEntry>(), cache.library(directory, AudioFilters()))
    }

    @Test
    fun durationsSurviveReopenAndRespectStatsAndGrantIdentity() = runBlocking(Dispatchers.IO) {
        val temporaryDirectory = Files.createTempDirectory("music-cache-").toFile()
        val path = temporaryDirectory.resolve("cache.db")
        val files = (1..497).map(::song)
        try {
            withDatabase(Room.databaseBuilder(app, MusicCacheDatabase::class.java, path.absolutePath).build()) { disk ->
                val first = MusicCache(app, disk)
                first.storeDurations(files.associateWith { 60_000L }, first.revision())
            }
            withDatabase(Room.databaseBuilder(app, MusicCacheDatabase::class.java, path.absolutePath).build()) { disk ->
                val reopened = MusicCache(app, disk)
                assertEquals(files.associateWith { 60_000L }, reopened.durations(files))
                val changed = listOf(files.first().copy(size = 101), files[1].copy(modified = 2000),
                    files[2].copy(name = "renamed.flac"), files[3].copy(treeUri = "other-tree"),
                    files[4].copy(modified = null))
                assertTrue(reopened.durations(changed).isEmpty())
            }
        } finally {
            temporaryDirectory.listFiles().orEmpty().forEach { it.delete() }
            temporaryDirectory.delete()
        }
    }

    @Test
    fun directoryRefreshPreservesUnchangedRowsAndInvalidatesChangedFiles() = runBlocking(Dispatchers.IO) {
        val files = (1..3).map(::song)
        cache.storeDirectory(directory, files, cache.revision())
        cache.storeDurations(files.associateWith { 30_000L }, cache.revision())
        cache.storeLibrary(directory, AudioFilters(), files.map(::libraryEntry), cache.revision())
        fun childRowId(): Long = database.openHelper.readableDatabase.query(
            "SELECT rowid FROM directory_children WHERE uri = 'song-1'").use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
        val originalId = childRowId()
        cache.storeDirectory(directory, files, cache.revision())
        assertEquals(originalId, childRowId())
        val updated = listOf(files[0], files[1].copy(size = 200))
        cache.storeDirectory(directory, updated, cache.revision())
        assertEquals(updated, cache.directory("tree", "folder", AudioFilters())?.children)
        assertEquals(mapOf(files[0] to 30_000L), cache.durations(files))
        assertNull(cache.library(directory, AudioFilters()))
        assertEquals(setOf(files[0]), cache.libraryEntries(files).keys)
        assertEquals(2, database.cache().childCount())
    }

    @Test
    fun invalidationRejectsEarlierWritesAndClearKeepsOtherTrees() = runBlocking(Dispatchers.IO) {
        val file = song(1)
        val other = song(2).copy(treeUri = "other-tree")
        val oldRevision = cache.revision()
        cache.storeDirectory(directory, listOf(file), oldRevision)
        cache.storeDurations(mapOf(file to 30_000L, other to 60_000L), oldRevision)
        cache.invalidate(file)
        cache.storeDirectory(directory, listOf(file), oldRevision)
        cache.storeDurations(mapOf(file to 30_000L), oldRevision)
        assertNull(cache.directory("tree", "folder", AudioFilters()))
        assertEquals(mapOf(other to 60_000L), cache.durations(listOf(file, other)))
        cache.storeDurations(mapOf(file to 30_000L), cache.revision())
        cache.clearTree("tree")
        assertEquals(mapOf(other to 60_000L), cache.durations(listOf(file, other)))
    }

    @Test
    fun cachedDirectoryUsesCurrentFiltersAndDoesNotPersistUnreliableDurations() = runBlocking(Dispatchers.IO) {
        val short = song(1)
        val long = song(2)
        val unknown = song(3).copy(modified = null)
        cache.storeDirectory(directory, listOf(short, long, unknown), cache.revision())
        cache.storeDurations(mapOf(short to 9_000L, long to 60_000L, unknown to 5_000L), cache.revision())
        assertEquals(listOf(long, unknown), cache.directory("tree", "folder", AudioFilters(20))?.children)
        assertTrue(cache.durations(listOf(unknown)).isEmpty())
        assertNull(cache.directory("tree", "folder", AudioFilters(excludedPaths = listOf("专辑"))))
    }

    @Test
    fun mp3PreviewRejectsOldMissingArtworkAndAcceptsRebuiltPreview() = runBlocking(Dispatchers.IO) {
        val file = song(1).copy(name = "sample.mp3")
        val track = LocalTrack(file.name, "Song", emptyList(), null, null)
        val legacy = """{"title":"Song","artists":[]}"""
        database.cache().putPreview(PreviewRow(file.treeUri, file.uri, file.name, 100, 1000, legacy, null, 1))
        assertNull(cache.preview(file))
        cache.storePreview(file, FilePreview(track, null), cache.revision())
        assertEquals(track, cache.preview(file)?.track)
    }

    @Test
    fun previewRoundTripsAndCorruptPreviewDoesNotDisableDurationCache() = runBlocking(Dispatchers.IO) {
        val file = song(1)
        val track = LocalTrack(file.name, null, listOf("歌手甲", "歌手乙"), "专辑", 60_000)
        cache.storePreview(file, FilePreview(track, null), cache.revision())
        assertEquals(track, cache.preview(file)?.track)
        assertNull(cache.preview(file.copy(size = 200)))
        database.cache().putPreview(PreviewRow(file.treeUri, file.uri, file.name, 100, 1000, "broken JSON", null, 1))
        assertNull(cache.preview(file))
        cache.storeDurations(mapOf(file to 60_000L), cache.revision())
        assertEquals(mapOf(file to 60_000L), cache.durations(listOf(file)))
    }

    @Test
    fun retainDropsDeletedAndMovedFilesButKeepsOtherTrees() = runBlocking(Dispatchers.IO) {
        val live = song(1)
        val deleted = song(2)
        val moved = song(3)
        val other = song(4).copy(treeUri = "other-tree")
        val track = LocalTrack("gone.flac", "旧歌", listOf("歌手"), "专辑", null)
        cache.storeDirectory(directory, listOf(live, deleted, moved), cache.revision())
        cache.storeDurations(mapOf(live to 30_000L, deleted to 40_000L, moved to 50_000L, other to 60_000L), cache.revision())
        cache.storePreview(deleted, FilePreview(track.copy(fileName = deleted.name), null), cache.revision())
        cache.storePreview(other, FilePreview(track.copy(fileName = other.name), null), cache.revision())
        cache.retain("tree", setOf(directory.uri, live.uri))
        assertEquals(mapOf(live to 30_000L), cache.durations(listOf(live, deleted, moved)))
        assertEquals(mapOf(other to 60_000L), cache.durations(listOf(other)))
        assertNull(cache.preview(deleted))
        assertEquals(track.copy(fileName = other.name), cache.preview(other)?.track)
        assertEquals(listOf(live), cache.directory("tree", "folder", AudioFilters())?.children)
    }

    @Test
    fun trimmingDirectorySnapshotsKeepsLibraryPreviewsUntilACompletedSweepConfirmsDeletion() = runBlocking(Dispatchers.IO) {
        val oldest = MusicDocument("tree", "old-folder", null, "old", isDirectory = true)
        val file = song(1).copy(parentUri = "old-folder", relativePath = "old/1.flac")
        val unvisited = song(2).copy(parentUri = "unvisited-folder")
        val track = LocalTrack(file.name, "Song", listOf("Artist"), "Album", null)
        cache.storeDirectory(oldest, listOf(file), cache.revision())
        cache.storePreview(file, FilePreview(track, null), cache.revision())
        cache.storePreview(unvisited, FilePreview(track.copy(fileName = unvisited.name), null), cache.revision())
        cache.storeDurations(mapOf(file to 30_000L, unvisited to 60_000L), cache.revision())
        repeat(128) { index ->
            cache.storeDirectory(MusicDocument("tree", "d$index", null, "d$index", isDirectory = true), emptyList(), cache.revision())
        }
        assertNull(cache.directory("tree", "old-folder", AudioFilters()))
        assertEquals(track, cache.preview(file)?.track)
        assertEquals(track.copy(fileName = unvisited.name), cache.preview(unvisited)?.track)
        assertEquals(mapOf(file to 30_000L, unvisited to 60_000L), cache.durations(listOf(file, unvisited)))
        cache.retain("tree", setOf(oldest.uri, file.uri))
        assertEquals(track, cache.preview(file)?.track)
        assertNull(cache.preview(unvisited))
        assertEquals(mapOf(file to 30_000L), cache.durations(listOf(file, unvisited)))
    }

    @Test
    fun previewsIgnoreChangedFiles() = runBlocking(Dispatchers.IO) {
        val file = song(1)
        val changed = song(2)
        val track = LocalTrack(file.name, "Song", listOf("歌手"), "专辑", 60_000)
        cache.storePreview(file, FilePreview(track, null), cache.revision())
        cache.storePreview(changed, FilePreview(track.copy(fileName = changed.name), null), cache.revision())
        assertEquals(track, cache.preview(file)?.track)
        assertNull(cache.preview(changed.copy(size = 200)))
    }
}
