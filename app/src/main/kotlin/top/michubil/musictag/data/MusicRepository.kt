package top.michubil.musictag.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import top.michubil.musictag.data.cache.MusicCache
import top.michubil.musictag.data.edit.*
import top.michubil.musictag.data.model.CoverImage
import top.michubil.musictag.data.model.CoverImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.michubil.musictag.data.flac.FlacCodec
import top.michubil.musictag.data.flac.SafeFlacEditor
import top.michubil.musictag.data.id3.Id3Codec
import top.michubil.musictag.data.id3.SafeMp3Editor
import top.michubil.musictag.data.lyrics.LyricsCodec
import top.michubil.musictag.data.model.LocalTrack
import top.michubil.musictag.data.model.MatchResult
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.model.map
import top.michubil.musictag.data.network.MetadataSourcesClient
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.RenameInputs
import top.michubil.musictag.data.rename.AudioTextMetadata
import top.michubil.musictag.data.rename.RenameSource
import top.michubil.musictag.data.rename.SafFileRenamer
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.SafAudioCommitter
import top.michubil.musictag.data.storage.SafStorage
import top.michubil.musictag.data.storage.canSafelyReplace
import top.michubil.musictag.data.storage.expandDocuments
import top.michubil.musictag.data.storage.isReservedDocumentName
import top.michubil.musictag.data.storage.sortDocuments
import top.michubil.musictag.data.wav.SafeWavEditor
import top.michubil.musictag.data.wav.WavCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

data class FilePreview(val track: LocalTrack?, val artwork: Bitmap?)
data class TagEditorContent(val inputs: TagEditInputs, val artwork: Bitmap?)
data class SelectedCover(val image: CoverImage, val artwork: Bitmap)

class MusicRepository(
    context: Context,
    private val client: MetadataSourcesClient,
    private val flacEditor: SafeFlacEditor,
    private val mp3Editor: SafeMp3Editor,
    private val wavEditor: SafeWavEditor,
) {
    private val appContext = context.applicationContext
    private val storage = SafStorage(context)
    private val browseCache = MusicCache(context)
    private val audioFilter = AudioFileFilter(probe = { probeDuration(it) })
    private val renamer = SafFileRenamer(storage)
    private val networkSlots = Semaphore(4)
    private val committer = SafAudioCommitter(storage, File(context.noBackupFilesDir, "saf-writes"))
    private val commitGate = SerialGate()
    private val flacEdit = SerialGate()
    private val mp3Edit = SerialGate()
    private val wavEdit = SerialGate()
    private val workDirectory = File(context.cacheDir, "saf-work").apply {
        check(isDirectory || mkdirs()) { "无法创建音频工作目录" }
        // Only disposable private working copies; recovery records and originals live elsewhere.
        listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
    }

    suspend fun authorizeTree(treeUri: String, offeredFlags: Int): MusicDocument {
        check(committer.pendingTrees().all { it == treeUri }) { "请先重新授权原文件夹并完成文件恢复，再更换文件夹" }
        val alreadyGranted = storage.hasGrant(treeUri)
        storage.takeGrant(treeUri, offeredFlags)
        try {
            return storage.root(treeUri).also {
                require(it.canCreate) { "此文件夹不允许创建文件，请选择可写入的文件夹" }
                audioFilter.clear()
                browseCache.clearTree(treeUri)
            }
        } catch (error: Exception) {
            if (!alreadyGranted) runCatching { storage.releaseGrant(treeUri) }
            throw error
        }
    }

    suspend fun releaseTree(treeUri: String) {
        browseCache.clearTree(treeUri)
        storage.releaseGrant(treeUri)
    }
    fun hasGrant(treeUri: String) = storage.hasGrant(treeUri)
    fun root(treeUri: String) = storage.root(treeUri)
    suspend fun recover(treeUri: String) {
        if (treeUri in committer.pendingTrees()) clearBrowseCache(treeUri)
        commitGate.run { committer.recover(treeUri) }
    }

    fun directory(treeUri: String, uri: String): MusicDocument =
        storage.document(treeUri, uri).also { require(it.isDirectory) { "目录已不可用" } }

    suspend fun clearBrowseCache(treeUri: String) {
        audioFilter.clear()
        browseCache.clearTree(treeUri)
    }

    internal suspend fun cachedDirectory(tree: String, uri: String, sort: FileSort, descending: Boolean, filters: AudioFilters) =
        browseCache.directory(tree, uri, filters)?.let { it.copy(children = sortDocuments(it.children, sort, descending)) }

    private suspend fun invalidateCache(document: MusicDocument) {
        audioFilter.invalidate(document.uri)
        browseCache.invalidate(document)
    }

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
        browseCache.retain(root.treeUri, scanTreeContents(root).second)
    }

    internal suspend fun buildSearchIndex(
        root: MusicDocument,
        filters: AudioFilters,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): List<SearchEntry> {
        val context = currentCoroutineContext()
        val (files, liveUris) = scanTreeContents(root)
        browseCache.retain(root.treeUri, liveUris)
        val searchable = filterAudio(files, filters, onProgress)
        return LocalFileWork.map(searchable, onProgress) { document ->
            context.ensureActive()
            readSearchEntry(document)
        }
    }

    private suspend fun scanTreeContents(root: MusicDocument): Pair<List<MusicDocument>, Set<String>> {
        val context = currentCoroutineContext()
        val liveUris = linkedSetOf(root.uri)
        val files = expandDocuments(listOf(root), recursive = true) { directory ->
            context.ensureActive()
            liveUris.add(directory.uri)
            visibleChildren(directory).also { children -> children.forEach { liveUris.add(it.uri) } }
        }
        return files to liveUris
    }

    private suspend fun readSearchEntry(document: MusicDocument): SearchEntry = try {
        val metadata = readTextMetadata(document)
        SearchEntry(document, TagSearch.fields(document.name, metadata = metadata), TagSearch.track(document.name, metadata))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        SearchEntry(document, listOf(document.name), null)
    }

    private suspend fun readTextMetadata(document: MusicDocument): AudioTextMetadata {
        val context = currentCoroutineContext()
        return if (document.extension == "flac") {
            storage.read(document).use { FlacCodec.readTextMetadata(it) { context.ensureActive() } }
        } else withLocalCopy(document, previewOnly = true) { file ->
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
        return withLocalCopy(document, previewOnly = true) { file ->
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

    private fun decodeArtworkCandidates(candidates: Sequence<ByteArray>, maximumSize: Int): Bitmap? {
        var foundPicture = false
        for (bytes in candidates) {
            foundPicture = true
            decodeArtwork(bytes, maximumSize)?.let { return it }
        }
        // A decode failure must not become a persistent "no artwork" preview.
        check(!foundPicture) { "无法解码封面图片" }
        return null
    }

    private fun decodeArtwork(bytes: ByteArray, maximumSize: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maximumSize) {
                val scale = longest.toFloat() / maximumSize
                decoder.setTargetSize(
                    (info.size.width / scale).toInt().coerceAtLeast(1),
                    (info.size.height / scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readLocalTrack(file: File): LocalTrack = AudioMetadataReader.readTrack(file)

    private suspend fun trackForMatching(document: MusicDocument, file: File): LocalTrack {
        val track = readLocalTrack(file).copy(fileName = document.name)
        if (track.durationMs != null) return track
        val cached = browseCache.durations(listOf(document))[document]
        return track.copy(durationMs = cached ?: probeDuration(document))
    }

    private fun tagReadFailure(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "无法读取标签"

    suspend fun candidates(document: MusicDocument, options: ScrapeOptions): List<MatchResult> {
        val track = withLocalCopy(document, previewOnly = true) { trackForMatching(document, it) }
        return networkSlots.withPermit { client.candidates(track, options) }
    }

    suspend fun readRenameInputs(
        selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): RenameInputs =
        withContext(LocalFileWork.dispatcher) {
            val documents = expandSelection(selection, recursive, filters)
            val sources = LocalFileWork.map(documents, onProgress) { document ->
                currentCoroutineContext().ensureActive()
                try {
                    RenameSource(document, readTextMetadata(document))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    RenameSource(document, error = tagReadFailure(error))
                }
            }
            val siblings = LocalFileWork.map(documents.distinctBy { it.treeUri to it.parentUri }) { document ->
                currentCoroutineContext().ensureActive()
                val parentUri = document.parentUri ?: return@map null
                // Check every sibling, including documents hidden from the audio browser.
                try { parentUri to storage.browsingChildren(directory(document.treeUri, parentUri)) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            }.filterNotNull().toMap()
            RenameInputs(sources, siblings)
        }

    suspend fun rename(entry: RenameEntry): MusicDocument = withContext(LocalFileWork.dispatcher) {
        currentCoroutineContext().ensureActive()
        val parent = directory(entry.document.treeUri, requireNotNull(entry.document.parentUri))
        withContext(NonCancellable) {
            try { renamer.rename(entry, parent) }
            finally { invalidateCache(entry.document) }
        }
    }

    suspend fun readTagEditorContent(selection: List<MusicDocument>, recursive: Boolean, filters: AudioFilters): TagEditorContent =
        withContext(LocalFileWork.dispatcher) {
            val singleFile = selection.size == 1 && !selection.single().isDirectory
            val documents = expandSelection(selection, recursive, filters)
            val results: List<Result<Pair<TagEditSource, Bitmap?>>> = LocalFileWork.map(documents) { document ->
                currentCoroutineContext().ensureActive()
                try {
                    withDigestedLocalCopy(document) { file, digest ->
                        val parsed = AudioMetadataReader.readEditor(file, includeArtwork = singleFile)
                        val source = TagEditSource(document, digest, parsed.tags)
                        val artwork = if (singleFile) {
                            runCatching { decodeArtworkCandidates(parsed.pictures.getOrThrow(), 1024) }.getOrNull()
                        } else null
                        Result.success(source to artwork)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    Result.failure(error)
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

    suspend fun editTags(source: TagEditSource, mutation: TagMutation) {
        val document = source.document
        val parent = directory(document.treeUri, requireNotNull(document.parentUri))
        withDigestedLocalCopy(document) { file, originalDigest ->
            check(originalDigest == source.digest) { "文件在读取标签后发生变化，请重新读取后再编辑" }
            editAndCommit(document, parent, originalDigest, file, mutation.metadata, mutation.options)
        }
    }

    suspend fun scrape(document: MusicDocument, options: ScrapeOptions, forcedCandidate: SongCandidate?) {
        val parent = directory(document.treeUri, requireNotNull(document.parentUri))
        require(canSafelyReplace(document, parent)) {
            "${document.name}：提供方不支持安全替换所需的创建、重命名和删除操作"
        }
        withDigestedLocalCopy(document) { file, originalDigest ->
            val track = trackForMatching(document, file)
            val downloaded = networkSlots.withPermit { client.metadata(track, options, forcedCandidate) }
            val metadata = if (options.formatLyricsTimeline) {
                downloaded.copy(lyrics = downloaded.lyrics.map(LyricsCodec::formatTimeline))
            } else downloaded
            editAndCommit(document, parent, originalDigest, file, metadata, options)
        }
    }

    private suspend fun editAndCommit(
        document: MusicDocument, parent: MusicDocument, originalDigest: String,
        file: File, metadata: ScrapedMetadata, options: ScrapeOptions,
    ) {
        when (document.extension) {
            "flac" -> flacEdit.run { flacEditor.update(file, metadata, options) }
            "mp3" -> mp3Edit.run { mp3Editor.update(file, metadata, options) }
            "wav" -> wavEdit.run { wavEditor.update(file, metadata, options) }
            else -> error("不支持的音频格式")
        }
        currentCoroutineContext().ensureActive()
        // Once committing, finish or reconcile before honoring coroutine cancellation.
        withContext(NonCancellable) {
            try { commitGate.run { committer.commit(document, parent, originalDigest, file) } }
            finally { invalidateCache(document) }
        }
    }

    private suspend fun <T> withLocalCopy(document: MusicDocument, previewOnly: Boolean = false, block: suspend (File) -> T): T =
        copyToWorkFile(document, previewOnly, calculateDigest = false) { file, _ -> block(file) }

    private suspend fun <T> withDigestedLocalCopy(document: MusicDocument, block: suspend (File, String) -> T): T =
        copyToWorkFile(document, previewOnly = false, calculateDigest = true) { file, digest -> block(file, requireNotNull(digest)) }

    private suspend fun <T> copyToWorkFile(
        document: MusicDocument, previewOnly: Boolean, calculateDigest: Boolean,
        block: suspend (File, String?) -> T,
    ): T = withContext(LocalFileWork.dispatcher) {
        require(document.isAudio) { "不支持的音频格式" }
        val file = File.createTempFile("audio-", ".${document.extension}", workDirectory)
        val digest = if (calculateDigest) MessageDigest.getInstance("SHA-256") else null
        try {
            storage.read(document).use { input ->
                file.outputStream().use outputCopy@{ output ->
                    if (previewOnly && document.extension in setOf("flac", "mp3")) {
                        val context = currentCoroutineContext()
                        copyPreviewMetadata(input, output, document.extension) { context.ensureActive() }
                        return@outputCopy
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            digest?.update(buffer, 0, count)
                        }
                    }
                }
            }
            block(file, digest?.digest()?.toHexString())
        } finally {
            file.delete()
        }
    }
}
