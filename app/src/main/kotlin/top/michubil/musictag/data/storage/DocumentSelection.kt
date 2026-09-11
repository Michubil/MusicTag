package top.michubil.musictag.data.storage

/** Iterative traversal keeps large/deep trees bounded by their actual contents, not a depth cap. */
internal fun expandDocuments(
    selection: List<MusicDocument>,
    recursive: Boolean,
    children: (MusicDocument) -> List<MusicDocument>,
): List<MusicDocument> {
    val queue = ArrayDeque(selection)
    val visited = mutableSetOf<String>()
    val files = linkedMapOf<String, MusicDocument>()
    while (queue.isNotEmpty()) {
        val document = queue.removeFirst()
        if (!visited.add(document.uri)) continue
        if (document.isDirectory) {
            val contents = children(document)
            if (recursive) queue.addAll(contents)
            else contents.filter(MusicDocument::isAudio).forEach { files.putIfAbsent(it.uri, it) }
        } else if (document.isAudio) files.putIfAbsent(document.uri, document)
    }
    return files.values.toList()
}
