package top.michubil.musictag.data.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.os.CancellationSignal
import kotlinx.coroutines.*
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import java.io.IOException
import java.io.InputStream
import top.michubil.musictag.data.LocalFileWork

class SafStorage(context: Context) : DocumentStore {
    private val resolver = context.contentResolver
    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    )
    private val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun hasGrant(treeUri: String): Boolean = resolver.persistedUriPermissions.any {
        it.uri.toString() == treeUri && it.isReadPermission && it.isWritePermission
    }

    fun takeGrant(treeUri: String, offeredFlags: Int) {
        require(offeredFlags and accessFlags == accessFlags) { "请选择允许读取和写入的文件夹" }
        require(offeredFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) { "该文件夹不支持保留授权" }
        val uri = treeUri.toUri()
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择文件夹" }
        resolver.takePersistableUriPermission(uri, accessFlags)
    }

    fun releaseGrant(treeUri: String) {
        if (hasGrant(treeUri)) resolver.releasePersistableUriPermission(treeUri.toUri(), accessFlags)
    }

    fun root(treeUri: String): MusicDocument {
        require(hasGrant(treeUri)) { "文件夹授权已失效，请重新选择" }
        val tree = treeUri.toUri()
        return document(treeUri, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString())
            .also { require(it.isDirectory) { "授权位置不是文件夹" } }.copy(relativePath = "")
    }

    fun document(treeUri: String, documentUri: String, parentUri: String? = null): MusicDocument {
        val tree = treeUri.toUri()
        val uri = documentUri.toUri()
        require(uri.scheme == "content" && uri.authority == tree.authority &&
            DocumentsContract.isTreeUri(uri) &&
            DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree)) {
            "此目录不属于当前授权的文件夹"
        }
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("文件或文件夹已不存在")
            cursor.document(treeUri, parentUri)
        } ?: throw IOException("无法读取文件夹")
    }

    fun relativePath(document: MusicDocument): String? = runCatching {
        val tree = document.treeUri.toUri()
        val target = document.uri.toUri()
        val ids = DocumentsContract.findDocumentPath(resolver, target)?.path ?: return@runCatching null
        require(ids.firstOrNull() == DocumentsContract.getTreeDocumentId(tree) &&
            ids.lastOrNull() == DocumentsContract.getDocumentId(target)) { "提供方返回了无效目录路径" }
        ids.drop(1).joinToString("/") { id ->
            document(document.treeUri, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()).name
        }
    }.getOrNull()

    suspend fun browsingChildren(directory: MusicDocument): List<MusicDocument> = coroutineScope {
        val signal = CancellationSignal()
        // Cancellation must still run when every local I/O worker is blocked in a provider query.
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            withContext(LocalFileWork.dispatcher) { children(directory, signal) }
        } finally {
            cancellation.cancel()
        }
    }

    override fun children(directory: MusicDocument): List<MusicDocument> = children(directory, null)

    private fun children(directory: MusicDocument, signal: CancellationSignal?): List<MusicDocument> {
        require(directory.isDirectory)
        val uri = directory.uri.toUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        return resolver.query(childrenUri, projection, null, null, null, signal)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    signal?.throwIfCanceled()
                    val child = cursor.document(directory.treeUri, directory.uri)
                    add(child.copy(relativePath = directory.relativePath?.let { if (it.isEmpty()) child.name else "$it/${child.name}" }))
                }
            }
        } ?: throw IOException("无法读取文件夹内容")
    }

    override fun read(document: MusicDocument): InputStream =
        resolver.openInputStream(document.uri.toUri()) ?: throw IOException("无法读取 ${document.name}")

    override fun create(directory: MusicDocument, name: String, mimeType: String): MusicDocument {
        val uri = DocumentsContract.createDocument(resolver, directory.uri.toUri(), mimeType, name)
            ?: throw IOException("无法创建临时文件")
        return document(directory.treeUri, uri.toString(), directory.uri)
    }

    override fun write(document: MusicDocument, source: InputStream) {
        val descriptor = resolver.openFileDescriptor(document.uri.toUri(), "rwt")
            ?: throw IOException("无法写入临时文件")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            source.copyTo(output)
            output.flush()
            output.fd.sync()
        }
    }

    override fun rename(document: MusicDocument, name: String): MusicDocument {
        val uri = DocumentsContract.renameDocument(resolver, document.uri.toUri(), name)
            ?: throw IOException("无法重命名 ${document.name}")
        // Providers may change document IDs on rename. Always use the returned URI.
        return document(document.treeUri, uri.toString(), document.parentUri)
    }

    override fun delete(document: MusicDocument) {
        if (!DocumentsContract.deleteDocument(resolver, document.uri.toUri())) {
            throw IOException("无法清理 ${document.name}")
        }
    }

    private fun Cursor.document(treeUri: String, parentUri: String?): MusicDocument {
        fun text(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
        fun number(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
        val flags = number(Document.COLUMN_FLAGS)?.toInt() ?: 0
        return MusicDocument(
            treeUri = treeUri,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri.toUri(), text(Document.COLUMN_DOCUMENT_ID)).toString(),
            parentUri = parentUri,
            name = text(Document.COLUMN_DISPLAY_NAME),
            isDirectory = text(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR,
            size = number(Document.COLUMN_SIZE),
            modified = number(Document.COLUMN_LAST_MODIFIED),
            canCreate = flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0,
            canRename = flags and Document.FLAG_SUPPORTS_RENAME != 0,
            canDelete = flags and Document.FLAG_SUPPORTS_DELETE != 0,
        )
    }
}
