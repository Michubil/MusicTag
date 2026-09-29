package top.michubil.musictag.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import top.michubil.musictag.data.cache.MusicCache
import top.michubil.musictag.data.edit.*
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.CoverImages
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.michubil.musictag.data.flac.FlacCodec
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.map
import top.michubil.musictag.data.rename.RenameInputs
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameSource
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.SafStorage
import top.michubil.musictag.data.storage.expandDocuments
import top.michubil.musictag.data.storage.isReservedDocumentName
import top.michubil.musictag.data.storage.sortDocuments
import top.michubil.musictag.data.wav.WavCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FilePreview(val track: LocalTrack?, val artwork: Bitmap?)
data class TagEditorContent(val inputs: TagEditInputs, val artwork: Bitmap?)
data class SelectedCover(val image: CoverImage, val artwork: Bitmap)

internal class MusicLibrary(
    context: Context,
    private val storage: SafStorage,
    private val browseCache: MusicCache,
    private val files: AudioFiles,
) {
    private val appContext = context.applicationContext
    private val audioFilter = AudioFileFilter(probe = { probeDuration(it) })

    suspend fun clearBrowseCache(treeUri: String) {
        browseCache.clearTree(treeUri)
    }

    internal suspend fun cachedDirectory(tree: String, uri: String, sort: FileSort, descending: Boolean, filters: AudioFilters) =
        browseCache.directory(tree, uri, filters)?.let { it.copy(children = sortDocuments(it.children, sort, descending)) }

    suspend fun list(
        directory: MusicDocument, sort: FileSort, descending: Boolean, filters: AudioFilters,
        onDirectories: suspend (List<MusicDocument>) -> Unit = {},
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): List<MusicDocument> {
        val located = if (filters.excludedPaths.isEmpty()) directory else resolveRelativePath(directory)
        if (filters.excludes(located)) return emptyList()
        val revision = browseCache.revision()
        val children = storage.browsingChildren(located).filter(::isVisibleChild)
        currentCoroutineContext().ensureActive()
        onDirectories(sortDocuments(children.filter { it.isDirectory && !filters.excludes(it) }, sort, descending))
        browseCache.storeDirectory(located, children, revision)
        return sortDocuments(filterAudio(children, filters, onProgress), sort, descending)
    }

    suspend fun expandSelection(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters): List<MusicDocument> {
        val context = currentCoroutineContext()
        val files = expandDocuments(selection.filterNot(filters::excludes), recursive) { directory ->
            context.ensureActive()
            visibleChildren(directory).filterNot(filters::excludes)
        }
        return filterAudio(files, filters)
    }

    internal suspend fun pruneMissingCacheEntries(root: MusicDocument) {
        val revision = browseCache.revision()
        browseCache.retain(root.treeUri, scanTreeContents(root).second, revision)
    }

    internal suspend fun indexLibrary(
        root: MusicDocument,
        filters: AudioFilters,
        forceRead: Boolean = false,
        onCached: suspend (List<LibraryEntry>) -> Unit = {},
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): List<LibraryEntry> {
        val context = currentCoroutineContext()
        if (forceRead) clearBrowseCache(root.treeUri)
        else browseCache.library(root, filters)?.let { onCached(it) }
        val scanRevision = browseCache.revision()
        val (files, liveUris) = scanTreeContents(root)
        val revision = browseCache.retain(root.treeUri, liveUris, scanRevision)
        val searchable = filterAudio(files, filters, onProgress)
        val known = browseCache.libraryEntries(searchable)
        val missing = searchable.filterNot(known::containsKey)
        val loaded = LocalFileWork.map(missing, onProgress) { document ->
            context.ensureActive()
            readLibraryEntry(document)
        }.associateBy { it.document }
        val entries = searchable.map { known[it] ?: loaded.getValue(it) }
        if (revision != null) browseCache.storeLibrary(root, filters, entries, revision)
        return entries
    }

    private suspend fun scanTreeContents(root: MusicDocument): Pair<List<MusicDocument>, Set<String>> {
        val context = currentCoroutineContext()
        val liveUris = linkedSetOf(root.uri)
        val files = expandDocuments(listOf(root), recursive = true) { directory ->
            context.ensureActive()
            liveUris.add(directory.uri)
            storage.browsingChildren(directory).filter(::isVisibleChild)
                .also { children -> children.forEach { liveUris.add(it.uri) } }
        }
        return files to liveUris
    }

    private suspend fun readLibraryEntry(document: MusicDocument): LibraryEntry = operationResult {
        val metadata = readTextMetadata(document)
        LibraryEntry(document, TagSearch.fields(document.name, metadata = metadata), TagSearch.track(document.name, metadata))
    }.getOrElse {
        LibraryEntry(document, listOf(document.name), null)
    }

    private suspend fun readTextMetadata(document: MusicDocument): AudioTextMetadata {
        val context = currentCoroutineContext()
        return if (document.extension == "flac") {
            storage.read(document).use { FlacCodec.readTextMetadata(it) { context.ensureActive() } }
        } else files.withLocalCopy(document, previewOnly = true) { file ->
            when (document.extension) {
                "mp3" -> Id3Codec.readTextMetadata(file)
                "wav" -> WavCodec.readTextMetadata(file)
                else -> error("不支持的音频格式")
            }
        }
    }

    private suspend fun filterAudio(
        documents: List<MusicDocument>, filters: AudioFilters,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): List<MusicDocument> {
        val candidates = documents.filterNot(filters::excludes)
        if (filters.minimumSeconds == 0) return candidates
        val revision = browseCache.revision()
        val known = browseCache.durations(candidates)
        val learned = ConcurrentHashMap<MusicDocument, Long>()
        val result = audioFilter.filter(candidates, filters, knownDurations = known,
            onDuration = { document, duration -> if (document !in known) learned[document] = duration },
            onProgress = onProgress)
        browseCache.storeDurations(learned, revision)
        return result
    }

    private fun visibleChildren(directory: MusicDocument): List<MusicDocument> =
        storage.children(directory).filter(::isVisibleChild)

    private fun isVisibleChild(document: MusicDocument): Boolean =
        !isReservedDocumentName(document.name) && (document.isDirectory || document.isAudio)

    private suspend fun resolveRelativePath(document: MusicDocument): MusicDocument {
        document.relativePath?.let { return document }
        storage.relativePath(document)?.let { return document.copy(relativePath = it) }
        // Resolve display-name paths inside the granted tree; document IDs are never filesystem paths.
        val queue = ArrayDeque<MusicDocument>()
        queue.add(storage.root(document.treeUri))
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = queue.removeFirst()
            if (!seen.add(current.uri)) continue
            if (current.uri == document.uri) return current
            queue.addAll(storage.browsingChildren(current).filter { it.isDirectory && !isReservedDocumentName(it.name) })
        }
        error("文件夹已不可用")
    }

    private fun probeDuration(document: MusicDocument): Long? {
        when (document.extension) {
            "flac" -> runCatching { storage.read(document).use(FlacCodec::readDuration) }.getOrNull()?.let { return it }
            "wav" -> runCatching { storage.read(document).use(WavCodec::readDuration) }.getOrNull()?.let { return it }
        }
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(appContext, document.uri.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }

    suspend fun preview(
        document: MusicDocument, cachedOnly: Boolean = false,
        onReady: suspend (FilePreview) -> Unit = {},
    ): FilePreview {
        val revision = browseCache.revision()
        browseCache.preview(document)?.let { onReady(it); return it }
        if (cachedOnly) return FilePreview(null, null).also { onReady(it) }
        return files.withLocalCopy(document, previewOnly = true) { file ->
            val parsed = runCatching { AudioMetadataReader.readPreview(file) }.getOrNull()
            val track = parsed?.track?.map { it.copy(fileName = document.name) }
            val artwork = runCatching { parsed?.pictures?.getOrThrow()?.let { decodeArtworkCandidates(it, 256) } }
            val result = FilePreview(track?.getOrNull(), artwork.getOrNull())
            // Keep cache work within the bounded preview worker, but let the row render first.
            onReady(result)
            if (track?.isSuccess == true && artwork.isSuccess) browseCache.storePreview(document, result, revision)
            result
        }
    }

    suspend fun trackForMatching(document: MusicDocument, file: File): LocalTrack {
        val track = AudioMetadataReader.readTrack(file).copy(fileName = document.name)
        if (track.durationMs != null) return track
        val cached = browseCache.durations(listOf(document))[document]
        return track.copy(durationMs = cached ?: probeDuration(document))
    }

    private fun tagReadFailure(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "无法读取标签"

    suspend fun readRenameInputs(
        selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): RenameInputs =
        withContext(LocalFileWork.dispatcher) {
            val documents = expandSelection(selection, recursive, filters)
            val sources = LocalFileWork.map(documents, onProgress) { document ->
                currentCoroutineContext().ensureActive()
                operationResult {
                    RenameSource(document, readTextMetadata(document))
                }.getOrElse {
                    RenameSource(document, error = tagReadFailure(it))
                }
            }
            val siblings = LocalFileWork.map(documents.distinctBy { it.treeUri to it.parentUri }) { document ->
                currentCoroutineContext().ensureActive()
                val parentUri = document.parentUri ?: return@map null
                // Check every sibling, including documents hidden from the audio browser.
                operationResult { parentUri to storage.browsingChildren(files.directory(document.treeUri, parentUri)) }.getOrNull()
            }.filterNotNull().toMap()
            RenameInputs(sources, siblings)
        }

    suspend fun readTagEditorContent(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters): TagEditorContent =
        withContext(LocalFileWork.dispatcher) {
            val singleFile = selection.size == 1 && !selection.single().isDirectory
            val documents = expandSelection(selection, recursive, filters)
            val results: List<Result<Pair<TagEditSource, Bitmap?>>> = LocalFileWork.map(documents) { document ->
                currentCoroutineContext().ensureActive()
                operationResult {
                    files.withDigestedLocalCopy(document) { file, digest ->
                        val parsed = AudioMetadataReader.readEditor(file, includeArtwork = singleFile)
                        val source = TagEditSource(document, digest, parsed.tags)
                        val artwork = if (singleFile) {
                            runCatching { decodeArtworkCandidates(parsed.pictures.getOrThrow(), 1024) }.getOrNull()
                        } else null
                        source to artwork
                    }
                }
            }
            val sources = results.mapNotNull { it.getOrNull()?.first }
            val failures = results.mapIndexedNotNull { index, result ->
                result.exceptionOrNull()?.let { "${documents[index].name}：${tagReadFailure(it)}" }
            }
            TagEditorContent(TagEditInputs(sources, failures), results.firstOrNull()?.getOrNull()?.second)
        }

    suspend fun readCover(uri: String): SelectedCover = withContext(LocalFileWork.dispatcher) {
        val bytes = requireNotNull(appContext.contentResolver.openInputStream(uri.toUri())) { "无法读取图片" }
            .use { it.readNBytes(CoverImages.MAX_BYTES + 1) }
        val image = CoverImages.read(bytes)
        val artwork = requireNotNull(decodeArtwork(bytes, 1024)) { "无法解码封面图片" }
        SelectedCover(image, artwork)
    }

}
