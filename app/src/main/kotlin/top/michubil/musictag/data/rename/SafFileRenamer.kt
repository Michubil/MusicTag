package top.michubil.musictag.data.rename

import top.michubil.musictag.data.storage.DocumentStore
import top.michubil.musictag.data.storage.MusicDocument

/** Rename only: no audio stream is opened for writing, and no conflicting file is deleted. */
class SafFileRenamer(private val store: DocumentStore) {
    fun rename(entry: RenameEntry, parent: MusicDocument): MusicDocument {
        require(entry.willRename) { "此文件无需或不能重命名" }
        val expected = entry.document
        val name = requireNotNull(entry.newName)
        require(parent.uri == expected.parentUri && parent.treeUri == expected.treeUri && parent.isDirectory)
        val siblings = store.children(parent)
        val current = siblings.singleOrNull { it.uri == expected.uri } ?: error("原文件已移动或不存在，请刷新")
        require(current.name == expected.name && current.isAudio && current.canRename &&
            (expected.size == null || current.size == expected.size) &&
            (expected.modified == null || current.modified == expected.modified)) { "文件已变化，请重新读取标签" }
        require(siblings.none { it.uri != current.uri && filenameKey(it.name) == filenameKey(name) }) {
            "同目录出现了重名文件，已跳过"
        }
        val renamed = store.rename(current, name)
        // Provider can return a new URI or adjust the name; never report an unconfirmed result as success.
        check(renamed.name == name && renamed.parentUri == parent.uri && renamed.treeUri == parent.treeUri) {
            "提供方返回的名称为 ${renamed.name}，请刷新检查实际结果"
        }
        return renamed
    }
}
