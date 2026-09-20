package top.michubil.musictag.data.rename

import top.michubil.musictag.data.model.supportedAudioExtensions
import top.michubil.musictag.data.storage.isReservedDocumentName
import java.text.Normalizer
import java.util.Locale

enum class RenameTag(val token: Char, val label: String) {
    TITLE('1', "标题"), ARTISTS('2', "艺术家"), ALBUM('3', "专辑"), DISC('4', "碟号"),
    TRACK('5', "音轨号"), YEAR('6', "年份"), COMMENT('7', "注释"), ALBUM_ARTISTS('8', "专辑艺术家"),
}

enum class RenamePreset(val label: String, val pattern: String) {
    TITLE("标题", "@1"),
    ARTIST_TITLE("艺术家 - 标题", "@2 - @1"),
    TITLE_ARTIST("标题-艺术家", "@1-@2"),
    TRACK_TITLE("音轨号. 标题", "@5. @1"),
    TRACK_ARTIST_TITLE("音轨号. 艺术家 - 标题", "@5. @2 - @1"),
    DISC_TRACK_TITLE("碟号音轨号. 标题", "@4@5. @1"),
    DISC_TRACK_ARTIST_TITLE("碟号音轨号. 艺术家 - 标题", "@4@5. @2 - @1"),
    CUSTOM("自定义", ""),
}

/** One-pass substitution: a tag value containing @2 is text, never another template. */
class FilenameTemplate(val pattern: String) {
    private val parts: List<Pair<String, RenameTag?>>

    init {
        require(pattern.isNotBlank()) { "请输入命名模板" }
        require(pattern.length <= 240) { "命名模板过长" }
        parts = buildList {
            val literal = StringBuilder()
            var index = 0
            while (index < pattern.length) {
                val char = pattern[index++]
                if (char != '@') {
                    literal.append(char)
                    continue
                }
                require(index < pattern.length) { "@ 后需要标签编号，输入 @@ 表示 @" }
                val token = pattern[index++]
                if (token == '@') literal.append('@') else {
                    val tag = RenameTag.entries.firstOrNull { it.token == token }
                        ?: error("不支持 @$token，请使用 @1 至 @8 或 @@")
                    if (literal.isNotEmpty()) { add(literal.toString() to null); literal.clear() }
                    add("" to tag)
                }
            }
            if (literal.isNotEmpty()) add(literal.toString() to null)
        }
        require(parts.any { it.second != null }) { "模板至少需要一个标签参数" }
    }

    fun filename(originalName: String, metadata: AudioTextMetadata): String {
        val extension = originalName.substringAfterLast('.', "")
        require(extension.lowercase(Locale.ROOT) in supportedAudioExtensions) { "不支持的音频扩展名" }
        val missing = parts.mapNotNull { it.second }.distinct().filter { metadata.values[it].isNullOrBlank() }
        require(missing.isEmpty()) { "缺少标签：${missing.joinToString("、") { it.label }}" }
        val raw = parts.joinToString("") { (literal, tag) -> if (tag == null) literal else metadata.values.getValue(tag) }
        val stem = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }.joinToString("")
            .trim().trimEnd('.', ' ')
        require(stem.isNotBlank() && stem != "." && stem != "..") { "生成的文件名为空" }
        require(!isReservedDocumentName(stem)) { "不能使用应用恢复文件的保留名称" }
        val result = "$stem.$extension"
        require(result.toByteArray(Charsets.UTF_8).size <= 255) { "文件名超过 255 字节，请简化模板或标签" }
        return result
    }
}

internal fun filenameKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFC).trimEnd('.', ' ').lowercase(Locale.ROOT)
