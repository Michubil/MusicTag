package top.michubil.musictag.data.io

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

object AtomicAudioFileRewriter {
    data class FileRegion(val offset: Long, val length: Long)

    fun replacePreservingRegion(
        source: File,
        sourceRegion: FileRegion,
        writeTemporary: (File) -> Unit,
        validateTemporary: (File) -> FileRegion,
    ) {
        val sourceLength = source.length()
        val sourceModified = source.lastModified()
        val temporary = File(source.parentFile, ".${source.name}.${UUID.randomUUID()}.tmp")
        try {
            writeTemporary(temporary)
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            val temporaryRegion = validateTemporary(temporary)
            require(source.length() == sourceLength && source.lastModified() == sourceModified) {
                "The source file changed during scraping"
            }
            require(
                regionDigest(source, sourceRegion)
                    .contentEquals(regionDigest(temporary, temporaryRegion)),
            ) { "Audio payload validation failed" }
            try {
                Files.move(
                    temporary.toPath(),
                    source.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                throw IllegalStateException("This storage does not support atomic file replacement")
            }
        } finally {
            temporary.delete()
        }
    }

    private fun regionDigest(file: File, region: FileRegion): ByteArray {
        require(region.offset >= 0 && region.length >= 0 && region.offset + region.length <= file.length()) {
            "Invalid audio payload region"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            input.channel.position(region.offset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            var remaining = region.length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0) { "Truncated audio payload" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest.digest()
    }
}
