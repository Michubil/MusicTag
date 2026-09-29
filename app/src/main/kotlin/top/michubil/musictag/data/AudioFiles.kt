package top.michubil.musictag.data

import android.content.Context
import top.michubil.musictag.data.cache.MusicCache
import top.michubil.musictag.data.edit.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.michubil.musictag.data.flac.SafeFlacEditor
import top.michubil.musictag.data.id3.SafeMp3Editor
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.ScrapedMetadata
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.SafFileRenamer
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.storage.SafAudioCommitter
import top.michubil.musictag.data.storage.SafStorage
import top.michubil.musictag.data.wav.SafeWavEditor
import java.io.File
import java.security.MessageDigest

internal class AudioFiles(
    context: Context,
    private val storage: SafStorage,
    private val browseCache: MusicCache,
    private val flacEditor: SafeFlacEditor = SafeFlacEditor(),
    private val mp3Editor: SafeMp3Editor = SafeMp3Editor(),
    private val wavEditor: SafeWavEditor = SafeWavEditor(),
) {
    private val renamer = SafFileRenamer(storage)
    private val committer = SafAudioCommitter(storage, File(context.noBackupFilesDir, "saf-writes"))
    // Permits cover complete SAF commits; recovery acquires all of them exclusively.
    private val commitSlots = Semaphore(LocalFileWork.parallelism)
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
    suspend fun recover(treeUri: String) {
        if (treeUri in committer.pendingTrees()) browseCache.clearTree(treeUri)
        withExclusiveCommitAccess { committer.recover(treeUri) }
    }

    fun directory(treeUri: String, uri: String): MusicDocument =
        storage.document(treeUri, uri).also { require(it.isDirectory) { "目录已不可用" } }

    suspend fun rename(entry: RenameEntry): MusicDocument = withContext(LocalFileWork.dispatcher) {
        currentCoroutineContext().ensureActive()
        val parent = directory(entry.document.treeUri, requireNotNull(entry.document.parentUri))
        withContext(NonCancellable) {
            try { renamer.rename(entry, parent) }
            finally { browseCache.invalidate(entry.document) }
        }
    }

    suspend fun editTags(source: TagEditSource, mutation: TagMutation) {
        val document = source.document
        val parent = directory(document.treeUri, requireNotNull(document.parentUri))
        withDigestedLocalCopy(document) { file, originalDigest ->
            check(originalDigest == source.digest) { "文件在读取标签后发生变化，请重新读取后再编辑" }
            editAndCommit(document, parent, originalDigest, file, mutation.metadata, mutation.options)
        }
    }

    suspend fun editAndCommit(
        document: MusicDocument, parent: MusicDocument, originalDigest: String,
        file: File, metadata: ScrapedMetadata, options: ScrapeOptions,
    ) {
        when (document.extension) {
            "flac" -> flacEditor.update(file, metadata, options)
            "mp3" -> mp3Editor.update(file, metadata, options)
            "wav" -> wavEditor.update(file, metadata, options)
            else -> error("不支持的音频格式")
        }
        currentCoroutineContext().ensureActive()
        // Once committing, finish or reconcile before honoring coroutine cancellation.
        withContext(NonCancellable) {
            try { commitSlots.withPermit { committer.commit(document, parent, originalDigest, file) } }
            finally { browseCache.invalidate(document) }
        }
    }

    private suspend fun <T> withExclusiveCommitAccess(block: () -> T): T {
        var acquired = 0
        try {
            repeat(LocalFileWork.parallelism) {
                commitSlots.acquire()
                acquired++
            }
            return block()
        } finally {
            repeat(acquired) { commitSlots.release() }
        }
    }

    suspend fun <T> withLocalCopy(document: MusicDocument, previewOnly: Boolean = false, block: suspend (File) -> T): T =
        copyToWorkFile(document, previewOnly, calculateDigest = false) { file, _ -> block(file) }

    suspend fun <T> withDigestedLocalCopy(document: MusicDocument, block: suspend (File, String) -> T): T =
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
