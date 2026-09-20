package top.michubil.musictag.data.rename

import top.michubil.musictag.data.storage.MusicDocument

data class RenameSource(val document: MusicDocument, val metadata: AudioTextMetadata? = null, val error: String? = null)

data class RenameEntry(val document: MusicDocument, val newName: String? = null, val error: String? = null) {
    val willRename: Boolean get() = error == null && newName != null && newName != document.name
}

data class RenameInputs(val sources: List<RenameSource>, val siblings: Map<String, List<MusicDocument>>)

fun planRenames(inputs: RenameInputs, template: FilenameTemplate): List<RenameEntry> {
    val occupied = inputs.siblings.mapValues { (_, files) -> files.groupBy { filenameKey(it.name) } }
    val entries = inputs.sources.map { source ->
        val document = source.document
        when {
            source.error != null -> RenameEntry(document, error = source.error)
            !document.canRename -> RenameEntry(document, error = "提供方不支持重命名")
            source.metadata == null -> RenameEntry(document, error = "无法读取标签")
            else -> runCatching { template.filename(document.name, source.metadata) }.fold(
                onSuccess = { name ->
                    val siblings = occupied[document.parentUri]
                    val conflict = siblings?.get(filenameKey(name)).orEmpty().any { it.uri != document.uri }
                    RenameEntry(document, name, when {
                        siblings == null -> "无法检查同目录文件名"
                        conflict -> "同目录已有此文件名"
                        else -> null
                    })
                },
                onFailure = { RenameEntry(document, error = it.message ?: "无法生成文件名") },
            )
        }
    }
    val duplicates = entries.filter { it.error == null && it.newName != null }
        .groupBy { it.document.parentUri to filenameKey(requireNotNull(it.newName)) }
        .filterValues { it.size > 1 }.keys
    return entries.map { entry ->
        if (entry.error == null && entry.newName != null &&
            (entry.document.parentUri to filenameKey(entry.newName)) in duplicates) {
            entry.copy(error = "多个所选文件生成了相同名称")
        } else entry
    }
}
