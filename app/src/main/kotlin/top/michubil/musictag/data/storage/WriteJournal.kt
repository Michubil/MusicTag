package top.michubil.musictag.data.storage

import top.michubil.musictag.data.model.supportedAudioExtensions
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val reservedDocumentPrefix = ".musictag-"

internal fun isReservedDocumentName(name: String): Boolean =
    name.startsWith(reservedDocumentPrefix, ignoreCase = true)

/** Only unfinished single-file commits, never scraping jobs, metadata or matching history. */
internal data class PendingWrite(
    val id: String,
    val treeUri: String,
    val parentUri: String,
    val originalName: String,
    val originalUri: String,
    val originalDigest: String,
    val replacementDigest: String,
    val extension: String,
    val stagedName: String = "${reservedDocumentPrefix}new-$id.$extension",
    val backupName: String = "${reservedDocumentPrefix}original-$id.$extension",
) {
    val directory get() = MusicDocument(treeUri, parentUri, null, "", isDirectory = true)

    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(extension in supportedAudioExtensions)
        require(originalName.isNotEmpty() && !isReservedDocumentName(originalName))
        require(stagedName.isNotEmpty() && backupName.isNotEmpty())
        require(setOf(originalName, stagedName, backupName).size == 3)
        require(originalDigest.matches(Regex("[0-9a-f]{64}")) && replacementDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

internal class WriteJournal(private val directory: File) {
    fun save(record: PendingWrite) {
        record.validate()
        check(directory.isDirectory || directory.mkdirs()) { "无法保存文件恢复信息" }
        val target = File(directory, "${record.id}.txn")
        val temporary = File(directory, "${record.id}.tmp")
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(1)
                listOf(record.id, record.treeUri, record.parentUri, record.originalName, record.originalUri,
                    record.originalDigest, record.replacementDigest, record.extension,
                    record.stagedName, record.backupName).forEach(output::writeUTF)
                output.flush()
                file.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun pending(): List<PendingWrite> = directory.listFiles().orEmpty()
        .filter { it.extension == "txn" }
        .map { file ->
            DataInputStream(file.inputStream()).use { input ->
                require(input.readInt() == 1) { "文件恢复信息版本无法识别" }
                PendingWrite(
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(),
                ).also { record ->
                    record.validate()
                    require(file.name == "${record.id}.txn" && input.read() == -1) { "文件恢复信息损坏" }
                }
            }
        }

    fun remove(record: PendingWrite) {
        val file = File(directory, "${record.id}.txn")
        check(!file.exists() || file.delete()) { "无法清理文件恢复信息" }
    }
}
