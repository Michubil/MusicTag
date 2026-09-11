package top.michubil.musictag.data.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * SAF has no atomic replace contract. Never truncate the original: upload and verify a sibling,
 * retain the original under a unique name, then promote the verified sibling. A durable journal
 * lets the next launch reconcile interruption before deleting either copy.
 */
class SafAudioCommitter internal constructor(
    private val store: DocumentStore,
    private val journal: WriteJournal,
) {
    constructor(store: DocumentStore, journalDirectory: File) : this(store, WriteJournal(journalDirectory))

    @Synchronized
    fun pendingTrees(): Set<String> = journal.pending().map { it.treeUri }.toSet()

    fun recover(treeUri: String) {
        journal.pending().filter { it.treeUri == treeUri }.forEach(::settle)
    }

    fun commit(original: MusicDocument, directory: MusicDocument, originalDigest: String, edited: File) {
        check(journal.pending().none { it.treeUri == original.treeUri }) { "请先完成未结束的文件恢复" }
        require(canSafelyReplace(original, directory)) {
            "此文件夹或文件不支持安全替换所需的创建、重命名和删除操作"
        }
        val replacementDigest = edited.inputStream().use(::documentDigest)
        if (originalDigest == replacementDigest) return
        var record = PendingWrite(
            UUID.randomUUID().toString(), original.treeUri, directory.uri, original.name, original.uri,
            originalDigest, replacementDigest, original.extension,
        )
        val before = store.children(directory)
        check(before.singleOrNull { it.name == original.name }?.uri == original.uri) {
            "原文件已移动、重命名或存在同名冲突"
        }
        check(digest(original) == originalDigest) { "原文件在刮削过程中发生变化" }
        journal.save(record)
        try {
            val requestedStagedName = record.stagedName
            val staged = store.create(directory, requestedStagedName, audioMimeType(original.extension))
            if (staged.name != requestedStagedName && staged.name != original.name) {
                record = record.copy(stagedName = staged.name)
                journal.save(record)
            }
            check(staged.name == requestedStagedName && staged.canRename && staged.canDelete) {
                "此提供方无法保留临时文件名称或不支持安全替换"
            }
            edited.inputStream().use { store.write(staged, it) }
            check(digest(staged) == replacementDigest) { "新文件上传后校验失败，原文件未改动" }
            check(digest(original) == originalDigest) { "原文件在刮削过程中发生变化" }
            val requestedBackupName = record.backupName
            val backup = store.rename(original, requestedBackupName)
            if (backup.name != requestedBackupName && backup.name != original.name) {
                record = record.copy(backupName = backup.name)
                journal.save(record)
            }
            check(backup.name == requestedBackupName && digest(backup) == originalDigest) { "原文件暂存后校验失败" }
            val installed = store.rename(staged, original.name)
            if (installed.name != original.name && installed.name != record.backupName) {
                record = record.copy(stagedName = installed.name)
                journal.save(record)
            }
            check(installed.name == original.name && digest(installed) == replacementDigest) { "新文件提交后校验失败" }
            check(settle(record) == Resolution.Committed)
        } catch (error: Exception) {
            val resolution = try {
                settle(record)
            } catch (recovery: Exception) {
                throw IOException(
                    "${original.name}：提交未结束，原文件或其副本已保留。请保持文件夹授权并重试恢复。\n${recovery.message}",
                    error,
                ).also { it.addSuppressed(recovery) }
            }
            // A provider may report an error after completing a rename; verify actual contents.
            if (resolution != Resolution.Committed) throw error
        }
    }

    private enum class Resolution { Committed, Restored }

    private fun settle(record: PendingWrite): Resolution {
        val files = store.children(record.directory)
        check(files.none { record.id in it.name && it.name != record.stagedName && it.name != record.backupName }) {
            "提供方更改了临时文件名，已保留原件和恢复信息，请在系统文件管理器中检查"
        }
        fun named(name: String): MusicDocument? {
            val matches = files.filter { it.name == name }
            check(matches.size <= 1) { "发现同名文件，请先在系统文件管理器中处理：$name" }
            return matches.singleOrNull()
        }
        val installed = named(record.originalName)
        val backup = named(record.backupName)
        val staged = named(record.stagedName)
        if (backup != null) check(digest(backup) == record.originalDigest) {
            "原文件副本内容发生变化，已保留所有文件"
        }
        val installedDigest = installed?.let(::digest)
        val resolution = when {
            installedDigest == record.replacementDigest -> {
                // Verify the final file again before retiring the only original copy.
                if (backup != null) store.delete(backup)
                Resolution.Committed
            }
            installedDigest == record.originalDigest -> {
                if (backup != null) store.delete(backup)
                Resolution.Restored
            }
            backup == null && installed?.uri == record.originalUri -> {
                // Another editor changed the untouched source while we prepared the replacement.
                // Keep its bytes and discard only our uncommitted sibling.
                Resolution.Restored
            }
            installed == null && backup != null -> {
                val restored = store.rename(backup, record.originalName)
                check(restored.name == record.originalName && digest(restored) == record.originalDigest) {
                    "原文件名称尚未恢复，副本已保留"
                }
                Resolution.Restored
            }
            else -> throw IOException("原文件位置或内容发生变化，已保留恢复信息及所有副本")
        }
        if (staged != null) store.delete(staged)
        journal.remove(record)
        return resolution
    }

    private fun digest(document: MusicDocument) = store.read(document).use(::documentDigest)
}

internal fun canSafelyReplace(original: MusicDocument, directory: MusicDocument): Boolean =
    directory.canCreate && original.canRename && original.canDelete

internal fun audioMimeType(extension: String): String = when (extension) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    else -> error("不支持的音频格式")
}

internal fun documentDigest(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().toHexString()
}
