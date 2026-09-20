package top.michubil.musictag.data

import top.michubil.musictag.data.storage.MusicDocument

data class AudioFilters(val minimumSeconds: Int = 0, val excludedPaths: List<String> = emptyList()) {
    init { require(minimumSeconds in minimumSecondsOptions) }

    fun allowsDuration(durationMs: Long?): Boolean =
        minimumSeconds == 0 || durationMs == null || durationMs >= minimumSeconds * 1000L

    fun excludes(path: String): Boolean = excludedPaths.any {
        path.equals(it, ignoreCase = true) || path.startsWith("$it/", ignoreCase = true)
    }

    fun excludes(document: MusicDocument): Boolean = excludes(document.relativePath.orEmpty())

    companion object {
        val minimumSecondsOptions = 0..60 step 10

        fun parsePaths(text: String): List<String> = text.lines().map { it.trim().replace('\\', '/').trim('/') }
            .filter(String::isNotEmpty).onEach { path ->
                require(path.split('/').none { it.isBlank() || it == "." || it == ".." } && ':' !in path) {
                    "请填写相对音乐文件夹的路径，例如 播客/缓存；不使用盘符、URI 或 .."
                }
            }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
    }
}
