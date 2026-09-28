package top.michubil.musictag.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.SourceSelection

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

    fun setSources(group: MetadataGroup, selections: List<SourceSelection>) {
        persist {
            putString(sourceKey(group), SourceSelections.encode(selections))
            putBoolean(SOURCES_MIGRATED, true)
            remove(legacySourceKey(group))
        }
    }

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
        storageTreeUri = storage.getString("storage_tree_uri", null),
        scrapeSources = loadSources(),
        audioFilters = AudioFilters(
            storage.getInt("minimum_audio_seconds", 0),
            AudioFilters.parsePaths(storage.getString("excluded_folder_paths", "").orEmpty()),
        ),
    )

    private fun loadSources(): ScrapeSources {
        val stored = MetadataGroup.entries.associateWith { SourceSelections.decode(storage.getString(sourceKey(it), null)) }
        if (storage.getBoolean(SOURCES_MIGRATED, false) && stored.values.all { it != null }) {
            return ScrapeSources(requireNotNull(stored.getValue(MetadataGroup.TAGS)),
                requireNotNull(stored.getValue(MetadataGroup.LYRICS)), requireNotNull(stored.getValue(MetadataGroup.COVER)))
        }
        val sources = ScrapeSources(
            stored[MetadataGroup.TAGS] ?: SourceSelections.migrate(storage.getString(legacySourceKey(MetadataGroup.TAGS), null)),
            stored[MetadataGroup.LYRICS] ?: SourceSelections.migrate(storage.getString(legacySourceKey(MetadataGroup.LYRICS), null)),
            stored[MetadataGroup.COVER] ?: SourceSelections.migrate(storage.getString(legacySourceKey(MetadataGroup.COVER), null)),
        )
        val editor = storage.edit()
        MetadataGroup.entries.forEach { group ->
            editor.putString(sourceKey(group), SourceSelections.encode(sources[group]))
            editor.remove(legacySourceKey(group))
        }
        check(editor.putBoolean(SOURCES_MIGRATED, true).commit()) { "无法保存音乐来源设置" }
        return sources
    }

    private fun sourceKey(group: MetadataGroup) = "sources_${group.name.lowercase(java.util.Locale.ROOT)}"

    private fun legacySourceKey(group: MetadataGroup) = "source_${group.name.lowercase(java.util.Locale.ROOT)}"

    private companion object {
        const val SOURCES_MIGRATED = "sources_migrated"
    }
}
