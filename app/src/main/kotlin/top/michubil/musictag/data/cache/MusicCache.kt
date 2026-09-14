package top.michubil.musictag.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.FilePreview
import top.michubil.musictag.data.LibraryEntry
import top.michubil.musictag.data.LocalFileWork
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.storage.MusicDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val MP3_ARTWORK_VERSION = 1

internal data class CachedDirectory(val directory: MusicDocument, val children: List<MusicDocument>)

/** All disk access is optional, serialized and off the UI thread. SAF remains authoritative. */
internal class MusicCache(context: Context, databaseOverride: MusicCacheDatabase? = null) {
    private val appContext = context.applicationContext
    private val database by lazy {
        databaseOverride ?: Room.databaseBuilder(appContext, MusicCacheDatabase::class.java,
            File(appContext.noBackupFilesDir, "music-browse-cache.db").absolutePath)
            .fallbackToDestructiveMigration(true)
            .build()
    }
    private val lock = Mutex()
    private val generation = AtomicLong()
    private var disabled = false

    fun revision(): Long = generation.get()

    private suspend fun <T> access(invalidate: Boolean = false, block: (MusicCacheDao) -> T): T? = withContext(LocalFileWork.dispatcher) {
        lock.withLock {
            // Advance the epoch under the same lock as deletion, so new readers cannot observe
            // old rows with an already advanced epoch while invalidation waits for the lock.
            if (invalidate) generation.incrementAndGet()
            if (disabled) return@withLock null
            try { block(database.cache()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                // A full disk or unavailable database must not prevent browsing or file commits.
                disabled = true
                generation.incrementAndGet()
                null
            }
        }
    }

    suspend fun clearTree(tree: String): Unit = withContext(NonCancellable) {
        access(invalidate = true) { dao ->
            database.runInTransaction {
                dao.clearDirectories(tree)
                dao.clearDurations(tree)
                dao.clearPreviews(tree)
                dao.removeLibrarySnapshot(tree)
                dao.clearLibraryEntries(tree)
            }
        }
    }

    suspend fun invalidate(document: MusicDocument): Unit = withContext(NonCancellable) {
        access(invalidate = true) { dao ->
            database.runInTransaction {
                dao.removeDuration(document.treeUri, document.uri)
                dao.removePreview(document.treeUri, document.uri)
                dao.invalidateLibraryFiles(document.treeUri, listOf(document.uri))
                dao.removeDirectory(document.treeUri, document.uri)
                document.parentUri?.let { dao.removeDirectory(document.treeUri, it) }
            }
        }
    }

    suspend fun directory(tree: String, uri: String, filters: AudioFilters): CachedDirectory? = withContext(LocalFileWork.dispatcher) {
        val revision = revision()
        val rows = access { dao ->
            val row = dao.directory(tree, uri) ?: return@access null
            Triple(row, dao.children(tree, uri), if (filters.minimumSeconds > 0) dao.durations(tree, uri) else emptyList())
        } ?: return@withContext null
        val snapshot = try {
            CachedDirectory(JSONObject(rows.first.payload).document(), rows.second.map {
                currentCoroutineContext().ensureActive()
                JSONObject(it.payload).document()
            })
        } catch (_: JSONException) { return@withContext null }
        if (snapshot.directory.treeUri != tree || snapshot.directory.uri != uri) return@withContext null
        // Unknown relative paths must be resolved from the provider before applying path exclusions.
        if (filters.excludedPaths.isNotEmpty() && snapshot.directory.relativePath == null) return@withContext null
        val durations = rows.third.associateBy { it.uri }
        val children = snapshot.children.filter { child ->
            !filters.excludes(child) &&
                (child.isDirectory || filters.allowsDuration(durations[child.uri]?.takeIf { it.matches(child) }?.duration))
        }
        if (revision != generation.get() || filters.excludes(snapshot.directory)) null
        else snapshot.copy(children = children)
    }

    suspend fun storeDirectory(directory: MusicDocument, children: List<MusicDocument>, revision: Long): Unit = withContext(LocalFileWork.dispatcher) {
        val context = currentCoroutineContext()
        val rows = children.associate { child ->
            context.ensureActive()
            child.uri to child.json().toString()
        }
        val payload = directory.json().toString()
        access { dao ->
            if (revision != generation.get()) return@access
            database.runInTransaction {
                // Remove stale file metadata when a complete listing confirms a deletion or change.
                val previous = dao.children(directory.treeUri, directory.uri).associateBy { it.uri }
                val removed = previous.keys.filter { it !in rows }
                val invalid = previous.values.filter { it.payload != rows[it.uri] }.map { it.uri }
                if (invalid.isNotEmpty()) generation.incrementAndGet()
                invalid.chunked(400).forEach { uris ->
                    context.ensureActive()
                    dao.removeDurations(directory.treeUri, uris)
                    dao.removePreviews(directory.treeUri, uris)
                    dao.removeDirectories(directory.treeUri, uris)
                    dao.invalidateLibraryFiles(directory.treeUri, uris)
                }
                // One row per child avoids putting a whole large folder into a CursorWindow row.
                if (children.size <= 50000) {
                    dao.putDirectory(DirectoryRow(directory.treeUri, directory.uri, payload, System.currentTimeMillis()))
                    removed.chunked(400).forEach { dao.removeChildren(directory.treeUri, directory.uri, it) }
                    val changed = children.mapIndexed { ordinal, child ->
                        DirectoryChildRow(directory.treeUri, directory.uri, child.uri, ordinal, rows.getValue(child.uri))
                    }.filter { it != previous[it.uri] }
                    changed.chunked(400).forEach { context.ensureActive(); dao.putChildren(it) }
                    dao.trimDirectories()
                    while (children.size > previous.size && dao.childCount() > 50000) {
                        val oldest = dao.oldestDirectory() ?: break
                        dao.removeDirectory(oldest.tree, oldest.uri)
                    }
                    // Album/search previews also belong to directories never opened in the browser.
                    // Their own limits and completed tree sweeps govern retention, not snapshot eviction.
                } else dao.removeDirectory(directory.treeUri, directory.uri)
                context.ensureActive()
            }
        }
    }

    /** Drop file rows that a completed tree walk no longer observed. Cancellation still finishes the delete. */
    suspend fun retain(tree: String, liveUris: Set<String>, revision: Long = revision()): Long? = withContext(NonCancellable) {
        access { dao ->
            if (revision != generation.get()) return@access null
            val retainedRevision = generation.incrementAndGet()
            database.runInTransaction {
                dao.previewUris(tree).filter { it !in liveUris }.chunked(400).forEach { dao.removePreviews(tree, it) }
                dao.durationUris(tree).filter { it !in liveUris }.chunked(400).forEach { dao.removeDurations(tree, it) }
                dao.directoryUris(tree).filter { it !in liveUris }.chunked(400).forEach { dao.removeDirectories(tree, it) }
                dao.childUris(tree).filter { it !in liveUris }.chunked(400).forEach { dao.removeChildrenByUri(tree, it) }
                dao.libraryUris(tree).filter { it !in liveUris }.chunked(400).forEach { dao.invalidateLibraryFiles(tree, it) }
            }
            retainedRevision
        }
    }

    /** A complete snapshot is for early display only; individual rows require fresh provider stats. */
    suspend fun library(root: MusicDocument, filters: AudioFilters): List<LibraryEntry>? = withContext(LocalFileWork.dispatcher) {
        val revision = revision()
        val rows = access { dao ->
            val snapshot = dao.librarySnapshot(root.treeUri) ?: return@access null
            if (snapshot.root != root.uri || snapshot.filters != filters.cacheKey()) return@access null
            dao.libraryEntries(root.treeUri).takeIf { it.size == snapshot.entryCount }
        } ?: return@withContext null
        val entries = rows.map {
            currentCoroutineContext().ensureActive()
            it.entry() ?: return@withContext null
        }
        entries.takeIf { revision == generation.get() }
    }

    suspend fun libraryEntries(documents: List<MusicDocument>): Map<MusicDocument, LibraryEntry> = withContext(LocalFileWork.dispatcher) {
        val revision = revision()
        val known = mutableMapOf<MusicDocument, LibraryEntry>()
        documents.filter { it.hasReliableStats }.groupBy { it.treeUri }.forEach { (tree, files) ->
            files.distinctBy { it.uri }.chunked(400).forEach { batch ->
                currentCoroutineContext().ensureActive()
                val byUri = batch.associateBy { it.uri }
                val rows = access { it.libraryEntriesForFiles(tree, byUri.keys.toList()) } ?: return@withContext emptyMap()
                rows.forEach eachRow@{ row ->
                    val entry = row.entry() ?: return@eachRow
                    val fresh = byUri[row.uri] ?: return@eachRow
                    if (fresh.matchesContent(entry.document.name, entry.document.size, entry.document.modified)) {
                        known[fresh] = LibraryEntry(fresh, entry.searchText, entry.track)
                    }
                }
            }
        }
        if (revision == generation.get()) known else emptyMap()
    }

    suspend fun storeLibrary(root: MusicDocument, filters: AudioFilters, entries: List<LibraryEntry>, revision: Long): Unit =
        withContext(LocalFileWork.dispatcher) {
            val context = currentCoroutineContext()
            val rows = if (entries.size > 20000) emptyList() else entries.mapIndexedNotNull { ordinal, entry ->
                context.ensureActive()
                val document = entry.document
                val track = entry.track ?: return@mapIndexedNotNull null
                if (document.treeUri != root.treeUri || !document.hasReliableStats) return@mapIndexedNotNull null
                val row = LibraryEntryRow(root.treeUri, document.uri, ordinal, document.json().toString(), entry.searchText, track.json().toString())
                row.takeIf { (it.document.length + it.searchText.length + it.track.length) <= 64 * 1024 }
            }
            val snapshot = LibrarySnapshotRow(root.treeUri, root.uri, filters.cacheKey(), entries.size)
            access { dao ->
                if (revision != generation.get()) return@access
                database.runInTransaction {
                    val previous = dao.libraryEntries(root.treeUri).associateBy { it.uri }
                    val liveUris = rows.mapTo(HashSet()) { it.uri }
                    previous.keys.filter { it !in liveUris }.chunked(400).forEach {
                        context.ensureActive()
                        dao.removeLibraryEntries(root.treeUri, it)
                    }
                    rows.filter { it != previous[it.uri] }.chunked(400).forEach { context.ensureActive(); dao.putLibraryEntries(it) }
                    // Failed reads and providers without reliable stats must be retried next time.
                    if (rows.size == entries.size) {
                        if (snapshot != dao.librarySnapshot(root.treeUri)) dao.putLibrarySnapshot(snapshot)
                    } else dao.removeLibrarySnapshot(root.treeUri)
                    context.ensureActive()
                }
            }
        }

    suspend fun durations(documents: List<MusicDocument>): Map<MusicDocument, Long> = withContext(LocalFileWork.dispatcher) {
        val revision = revision()
        val known = mutableMapOf<MusicDocument, Long>()
        documents.filter { it.isAudio && it.hasReliableStats }.groupBy { it.treeUri }.forEach { (tree, files) ->
            files.distinctBy { it.uri }.chunked(400).forEach { batch ->
                currentCoroutineContext().ensureActive()
                val byUri = batch.associateBy { it.uri }
                val rows = access { it.durationsForFiles(tree, byUri.keys.toList()) } ?: return@withContext emptyMap()
                rows.forEach { row ->
                    byUri[row.uri]?.takeIf { row.matches(it) }?.let { known[it] = row.duration }
                }
            }
        }
        if (revision == generation.get()) known else emptyMap()
    }

    suspend fun storeDurations(durations: Map<MusicDocument, Long>, revision: Long): Unit = withContext(LocalFileWork.dispatcher) {
        if (durations.isEmpty()) return@withContext
        val context = currentCoroutineContext()
        val now = System.currentTimeMillis()
        val rows = durations.mapNotNull { (document, duration) ->
            context.ensureActive()
            if (!document.hasReliableStats || duration <= 0) null
            else DurationRow(document.treeUri, document.uri, document.parentUri, document.name,
                requireNotNull(document.size), requireNotNull(document.modified), duration, now)
        }
        if (rows.isEmpty()) return@withContext
        access { dao ->
            if (revision == generation.get()) {
                database.runInTransaction {
                    rows.chunked(400).forEach { context.ensureActive(); dao.putDurations(it) }
                    dao.trimDurations()
                    context.ensureActive()
                }
            }
        }
    }

    suspend fun preview(document: MusicDocument): FilePreview? = withContext(LocalFileWork.dispatcher) {
        if (!document.hasReliableStats) return@withContext null
        val revision = revision()
        val row = access { it.preview(document.treeUri, document.uri) }?.takeIf {
            document.matchesContent(it.name, it.size, it.modified)
        } ?: return@withContext null
        val result = try {
            val track = parseTrack(document, row.track) ?: return@withContext null
            val artwork = row.artwork?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            }
            FilePreview(track, artwork)
        } catch (_: JSONException) { return@withContext null }
        currentCoroutineContext().ensureActive()
        result.takeIf { revision == generation.get() }
    }

    suspend fun storePreview(document: MusicDocument, preview: FilePreview, revision: Long): Unit = withContext(LocalFileWork.dispatcher) {
        val track = preview.track ?: return@withContext
        if (!document.hasReliableStats || revision != generation.get()) return@withContext
        val row = try {
            val artwork = preview.artwork?.let { bitmap ->
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)) return@withContext
                    output.toByteArray().also { if (it.size > 128 * 1024) return@withContext }
                }
            }
            val payload = track.json()
                .apply { if (document.extension == "mp3") put("mp3ArtworkVersion", MP3_ARTWORK_VERSION) }.toString()
            if (payload.toByteArray(Charsets.UTF_8).size > 64 * 1024) return@withContext
            PreviewRow(document.treeUri, document.uri, document.name, requireNotNull(document.size),
                requireNotNull(document.modified), payload, artwork, System.currentTimeMillis())
        } catch (_: IllegalArgumentException) { return@withContext }
        currentCoroutineContext().ensureActive()
        access { dao ->
            if (revision != generation.get()) return@access
            database.runInTransaction {
                dao.putPreview(row)
                dao.trimPreviews()
            }
        }
    }
}

private fun DurationRow.matches(document: MusicDocument) =
    tree == document.treeUri && uri == document.uri && document.matchesContent(name, size, modified)

private fun MusicDocument.json(): JSONObject = JSONObject().put("tree", treeUri).put("uri", uri)
    .put("parent", parentUri).put("name", name).put("directory", isDirectory).put("size", size)
    .put("modified", modified).put("create", canCreate).put("rename", canRename).put("delete", canDelete)
    .put("path", relativePath)

private fun JSONObject.document() = MusicDocument(getString("tree"), getString("uri"), nullableString("parent"),
    getString("name"), getBoolean("directory"), nullableLong("size"), nullableLong("modified"),
    getBoolean("create"), getBoolean("rename"), getBoolean("delete"), nullableString("path"))

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun AudioFilters.cacheKey(): String = JSONObject().put("minimumSeconds", minimumSeconds)
    .put("excludedPaths", JSONArray(excludedPaths)).toString()

private fun MusicCacheDao.invalidateLibraryFiles(tree: String, uris: List<String>) {
    invalidateLibrarySnapshot(tree, uris)
    removeLibraryEntries(tree, uris)
}

private fun LocalTrack.json(): JSONObject = JSONObject().put("title", title).put("artists", JSONArray(artists))
    .put("album", album).put("duration", durationMs).put("year", year).put("albumArtists", JSONArray(albumArtists))

private fun JSONObject.track(fileName: String) = LocalTrack(fileName, nullableString("title"),
    getJSONArray("artists").strings(), nullableString("album"), nullableLong("duration"),
    nullableLong("year")?.toInt(), getJSONArray("albumArtists").strings())

private fun LibraryEntryRow.entry(): LibraryEntry? = try {
    val source = JSONObject(document).document()
    if (source.treeUri != tree || source.uri != uri || !source.hasReliableStats) null
    else LibraryEntry(source, searchText, JSONObject(track).track(source.name))
} catch (_: JSONException) { null }

private fun parseTrack(document: MusicDocument, payload: String): LocalTrack? = try {
    val track = JSONObject(payload)
    // Older readers cached unclassified APIC pictures as missing artwork.
    if (document.extension == "mp3" && track.optInt("mp3ArtworkVersion", 0) != MP3_ARTWORK_VERSION) null
    else track.track(document.name)
} catch (_: JSONException) { null }
