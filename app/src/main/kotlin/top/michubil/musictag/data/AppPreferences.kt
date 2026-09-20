package top.michubil.musictag.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.SourceOrder

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class FileSort { NAME, TYPE, MODIFIED }

data class UserPreferences(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val fileSort: FileSort,
    val sortDescending: Boolean,
    val albumSort: AlbumSort,
    val albumMinColumns: Int,
    val formatLyricsTimeline: Boolean,
    val recursive: Boolean,
    val storageTreeUri: String?,
    val audioFilters: AudioFilters = AudioFilters(),
    val mp3TagVersion: Mp3TagVersion = Mp3TagVersion.V24,
    val scrapeSources: ScrapeSources = ScrapeSources(),
)

@SuppressLint("UseKtx")
class AppPreferences(context: Context) {
    private val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        persist { putString("theme", mode.name) }
    }

    fun setDynamicColor(enabled: Boolean) {
        persist { putBoolean("dynamic_color", enabled) }
    }

    fun setFileSort(sort: FileSort, descending: Boolean) {
        persist {
            putString("file_sort", sort.name)
            putBoolean("sort_descending", descending)
        }
    }

    fun setAlbumSort(sort: AlbumSort) {
        persist { putString("album_sort", sort.name) }
    }

    fun setAlbumMinColumns(columns: Int) {
        persist { putInt("album_min_columns", columns.coerceIn(2, 4)) }
    }

    fun setFormatLyricsTimeline(enabled: Boolean) {
        persist { putBoolean("format_lyrics_timeline", enabled) }
    }

    fun setRecursive(enabled: Boolean) {
        persist { putBoolean("recursive", enabled) }
    }

    fun setMp3TagVersion(version: Mp3TagVersion) {
        persist { putString("mp3_tag_version", version.name) }
    }

    fun setSourceOrder(group: MetadataGroup, order: SourceOrder) {
        persist { putString("source_${group.name.lowercase(java.util.Locale.ROOT)}", order.name) }
    }

    private fun sourceOrder(group: MetadataGroup): SourceOrder = SourceOrder.entries.firstOrNull {
        it.name == storage.getString("source_${group.name.lowercase(java.util.Locale.ROOT)}", null)
    } ?: SourceOrder.NETEASE_FIRST

    fun setAudioFilters(filters: AudioFilters) {
        persist {
            putInt("minimum_audio_seconds", filters.minimumSeconds)
            putString("excluded_folder_paths", filters.excludedPaths.joinToString("\n"))
        }
    }

    fun setStorageTreeUri(uri: String) {
        persist(commit = true) { putString("storage_tree_uri", uri) }
    }

    private inline fun persist(commit: Boolean = false, block: SharedPreferences.Editor.() -> Unit) {
        val editor = storage.edit()
        editor.block()
        if (commit) check(editor.commit()) { "无法保存文件夹授权" } else editor.apply()
        mutable.value = read()
    }

    private fun read() = UserPreferences(
        themeMode = storage.getString("theme", null)?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM,
        dynamicColor = storage.getBoolean("dynamic_color", true),
        fileSort = FileSort.valueOf(storage.getString("file_sort", FileSort.NAME.name)!!),
        sortDescending = storage.getBoolean("sort_descending", false),
        albumSort = AlbumSort.entries.firstOrNull { it.name == storage.getString("album_sort", null) } ?: AlbumSort.TITLE,
        albumMinColumns = storage.getInt("album_min_columns", 3).coerceIn(2, 4),
        formatLyricsTimeline = storage.getBoolean("format_lyrics_timeline", true),
        recursive = storage.getBoolean("recursive", false),
        mp3TagVersion = Mp3TagVersion.entries.firstOrNull {
            it.name == storage.getString("mp3_tag_version", null)
        } ?: Mp3TagVersion.V24,
        storageTreeUri = storage.getString("storage_tree_uri", null),
        scrapeSources = ScrapeSources(sourceOrder(MetadataGroup.TAGS), sourceOrder(MetadataGroup.LYRICS), sourceOrder(MetadataGroup.COVER)),
        audioFilters = AudioFilters(
            storage.getInt("minimum_audio_seconds", 0),
            AudioFilters.parsePaths(storage.getString("excluded_folder_paths", "").orEmpty()),
        ),
    )
}
