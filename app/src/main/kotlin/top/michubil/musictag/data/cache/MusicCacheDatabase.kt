package top.michubil.musictag.data.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "directories", primaryKeys = ["tree", "uri"], indices = [Index("savedAt")])
internal data class DirectoryRow(val tree: String, val uri: String, val payload: String, val savedAt: Long)

@Entity(tableName = "directory_children", primaryKeys = ["tree", "parent", "uri"],
    indices = [Index(value = ["tree", "parent", "ordinal"])],
    foreignKeys = [ForeignKey(entity = DirectoryRow::class, parentColumns = ["tree", "uri"],
        childColumns = ["tree", "parent"], onDelete = ForeignKey.CASCADE)])
internal data class DirectoryChildRow(val tree: String, val parent: String, val uri: String, val ordinal: Int, val payload: String)

@Entity(tableName = "durations", primaryKeys = ["tree", "uri"], indices = [Index("savedAt"), Index(value = ["tree", "parent"])])
internal data class DurationRow(
    val tree: String, val uri: String, val parent: String?, val name: String,
    val size: Long, val modified: Long, val duration: Long, val savedAt: Long,
)

@Entity(tableName = "previews", primaryKeys = ["tree", "uri"], indices = [Index("savedAt")])
internal data class PreviewRow(
    val tree: String, val uri: String, val name: String, val size: Long, val modified: Long,
    val track: String, val artwork: ByteArray?, val savedAt: Long,
)

@Entity(tableName = "library_snapshots", primaryKeys = ["tree"])
internal data class LibrarySnapshotRow(val tree: String, val root: String, val filters: String, val entryCount: Int)

@Entity(tableName = "library_entries", primaryKeys = ["tree", "uri"], indices = [Index(value = ["tree", "ordinal"])])
internal data class LibraryEntryRow(val tree: String, val uri: String, val ordinal: Int, val document: String, val searchText: String, val track: String)

@Dao
internal interface MusicCacheDao {
    @Query("SELECT * FROM library_snapshots WHERE tree = :tree")
    fun librarySnapshot(tree: String): LibrarySnapshotRow?

    @Query("SELECT * FROM library_entries WHERE tree = :tree ORDER BY ordinal")
    fun libraryEntries(tree: String): List<LibraryEntryRow>

    @Query("SELECT * FROM library_entries WHERE tree = :tree AND uri IN (:uris)")
    fun libraryEntriesForFiles(tree: String, uris: List<String>): List<LibraryEntryRow>

    @Query("SELECT uri FROM library_entries WHERE tree = :tree")
    fun libraryUris(tree: String): List<String>

    @Upsert
    fun putLibrarySnapshot(row: LibrarySnapshotRow)

    @Upsert
    fun putLibraryEntries(rows: List<LibraryEntryRow>)

    @Query("DELETE FROM library_snapshots WHERE tree = :tree")
    fun removeLibrarySnapshot(tree: String)

    @Query("DELETE FROM library_entries WHERE tree = :tree")
    fun clearLibraryEntries(tree: String)

    @Query("DELETE FROM library_entries WHERE tree = :tree AND uri IN (:uris)")
    fun removeLibraryEntries(tree: String, uris: List<String>)

    @Query("DELETE FROM library_snapshots WHERE tree = :tree AND EXISTS (SELECT 1 FROM library_entries WHERE tree = :tree AND uri IN (:uris))")
    fun invalidateLibrarySnapshot(tree: String, uris: List<String>)

    @Query("SELECT * FROM directories WHERE tree = :tree AND uri = :uri")
    fun directory(tree: String, uri: String): DirectoryRow?

    @Query("SELECT * FROM directory_children WHERE tree = :tree AND parent = :parent ORDER BY ordinal")
    fun children(tree: String, parent: String): List<DirectoryChildRow>

    @Query("SELECT COUNT(*) FROM directory_children")
    fun childCount(): Int

    @Query("SELECT * FROM directories ORDER BY savedAt, rowid LIMIT 1")
    fun oldestDirectory(): DirectoryRow?

    @Query("SELECT * FROM durations WHERE tree = :tree AND uri IN (:uris)")
    fun durationsForFiles(tree: String, uris: List<String>): List<DurationRow>

    @Query("SELECT * FROM durations WHERE tree = :tree AND parent = :parent")
    fun durations(tree: String, parent: String): List<DurationRow>

    @Query("SELECT * FROM previews WHERE tree = :tree AND uri = :uri")
    fun preview(tree: String, uri: String): PreviewRow?

    @Query("SELECT uri FROM directories WHERE tree = :tree")
    fun directoryUris(tree: String): List<String>

    @Query("SELECT uri FROM previews WHERE tree = :tree")
    fun previewUris(tree: String): List<String>

    @Query("SELECT uri FROM durations WHERE tree = :tree")
    fun durationUris(tree: String): List<String>

    @Query("SELECT DISTINCT uri FROM directory_children WHERE tree = :tree")
    fun childUris(tree: String): List<String>

    @Query("DELETE FROM directory_children WHERE tree = :tree AND uri IN (:uris)")
    fun removeChildrenByUri(tree: String, uris: List<String>)

    @Upsert
    fun putDirectory(row: DirectoryRow)

    @Upsert
    fun putChildren(rows: List<DirectoryChildRow>)

    @Upsert
    fun putDurations(rows: List<DurationRow>)

    @Upsert
    fun putPreview(row: PreviewRow)

    @Query("DELETE FROM directory_children WHERE tree = :tree AND parent = :parent AND uri IN (:uris)")
    fun removeChildren(tree: String, parent: String, uris: List<String>)

    @Query("DELETE FROM durations WHERE tree = :tree AND uri IN (:uris)")
    fun removeDurations(tree: String, uris: List<String>)

    @Query("DELETE FROM previews WHERE tree = :tree AND uri IN (:uris)")
    fun removePreviews(tree: String, uris: List<String>)

    @Query("DELETE FROM directories WHERE tree = :tree AND uri IN (:uris)")
    fun removeDirectories(tree: String, uris: List<String>)

    @Query("DELETE FROM directories WHERE tree = :tree")
    fun clearDirectories(tree: String)

    @Query("DELETE FROM durations WHERE tree = :tree")
    fun clearDurations(tree: String)

    @Query("DELETE FROM previews WHERE tree = :tree")
    fun clearPreviews(tree: String)

    @Query("DELETE FROM directories WHERE tree = :tree AND uri = :uri")
    fun removeDirectory(tree: String, uri: String)

    @Query("DELETE FROM durations WHERE tree = :tree AND uri = :uri")
    fun removeDuration(tree: String, uri: String)

    @Query("DELETE FROM previews WHERE tree = :tree AND uri = :uri")
    fun removePreview(tree: String, uri: String)

    @Query("DELETE FROM directories WHERE rowid IN (SELECT rowid FROM directories ORDER BY savedAt DESC, rowid DESC LIMIT -1 OFFSET 128)")
    fun trimDirectories()

    @Query("DELETE FROM durations WHERE rowid IN (SELECT rowid FROM durations ORDER BY savedAt DESC, rowid DESC LIMIT -1 OFFSET 20000)")
    fun trimDurations()

    @Query("DELETE FROM previews WHERE rowid IN (SELECT rowid FROM previews ORDER BY savedAt DESC, rowid DESC LIMIT -1 OFFSET 512)")
    fun trimPreviews()
}

// Disposable cache only. Recovery records and user settings never live in this database.
@Database(entities = [DirectoryRow::class, DirectoryChildRow::class, DurationRow::class, PreviewRow::class,
    LibrarySnapshotRow::class, LibraryEntryRow::class], version = 3, exportSchema = false)
internal abstract class MusicCacheDatabase : RoomDatabase() {
    abstract fun cache(): MusicCacheDao
}
