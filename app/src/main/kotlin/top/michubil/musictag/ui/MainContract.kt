package top.michubil.musictag.ui

import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.LocalFileWork
import top.michubil.musictag.data.ScanProgress
import top.michubil.musictag.data.FileSort
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.AlbumGroup
import top.michubil.musictag.data.AppRelease
import top.michubil.musictag.data.AlbumSort

enum class MainDialog { DURATION, PATH, THEME, FILE_SORT, ALBUM_SORT, ALBUM_COLUMNS }

internal val ScanProgress.fraction: Float?
    get() = if (total > 0) completed.toFloat() / total else null

data class MainUiState(
    val treeUri: String? = null,
    val root: MusicDocument? = null,
    val directory: MusicDocument? = null,
    val items: List<FileItem> = emptyList(),
    val artworkRevision: Int = 0,
    val selected: Set<String> = emptySet(),
    val editor: TagEditorState = TagEditorState(),
    val audioFilters: AudioFilters = AudioFilters(),
    val dialog: MainDialog? = null,
    val renamePreset: RenamePreset = RenamePreset.TITLE_ARTIST,
    val renameCustomPattern: String = "@1-@2",
    val renameLoading: Boolean = false,
    val renameReadProgress: ScanProgress? = null,
    val renameEntries: List<RenameEntry> = emptyList(),
    val renameError: String? = null,
    val policies: Map<MetadataField, FieldPolicy> = MetadataField.entries.associateWith { FieldPolicy() },
    val recursive: Boolean = false,
    val candidates: List<SongCandidate> = emptyList(),
    val candidateNotice: String? = null,
    val candidateError: String? = null,
    val loading: Boolean = true,
    val showingCachedContent: Boolean = false,
    val refreshing: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val busy: Boolean = false,
    val fileProgress: ScanProgress? = null,
    val storageGranted: Boolean = false,
    val storageError: String? = null,
    val recoveryError: String? = null,
    val message: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val fileSort: FileSort = FileSort.NAME,
    val sortDescending: Boolean = false,
    val formatLyricsTimeline: Boolean = true,
    val scrapeSources: ScrapeSources = ScrapeSources(),
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchItems: List<FileItem> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryReady: Boolean = false,
    val showingCachedLibrary: Boolean = false,
    val libraryRefreshing: Boolean = false,
    val libraryProgress: ScanProgress? = null,
    val checkingUpdate: Boolean = false,
    val availableUpdate: AppRelease? = null,
    val albums: List<AlbumGroup> = emptyList(),
    val albumCovers: Map<String, FileItem> = emptyMap(),
    val albumItems: List<FileItem> = emptyList(),
    val openedAlbumKey: String? = null,
    val openedAlbumTitle: String? = null,
    val albumSort: AlbumSort = AlbumSort.TITLE,
    val displayedAlbumSort: AlbumSort = AlbumSort.TITLE,
    val albumMinColumns: Int = 3,
) {
    val showStoragePicker: Boolean get() = !loading && !storageGranted
    val canRefresh: Boolean get() = storageGranted && !loading && !busy
    val canSearch: Boolean get() = storageGranted && !busy && root != null && storageError == null && recoveryError == null
    val canRefreshSearch: Boolean get() = canSearch && !libraryLoading
    val canSort: Boolean get() = if (searching) canSearch else canRefresh
    val viewingAlbum: Boolean get() = openedAlbumKey != null
    val visibleItems: List<FileItem> get() = when {
        searching -> searchItems
        viewingAlbum -> albumItems
        else -> items
    }
    val canSelectFiles: Boolean get() = !busy && storageGranted && storageError == null && recoveryError == null && when {
        searching -> !showingCachedLibrary
        viewingAlbum -> !libraryLoading && !showingCachedLibrary
        else -> !loading && !showingCachedContent
    }
    val canRefreshLibrary: Boolean get() = storageGranted && !libraryLoading && !busy && storageError == null && recoveryError == null
    val libraryLoadingMessage: String get() {
        val prefix = if (searching) "正在搜索" else "正在读取专辑"
        return libraryProgress?.let { "$prefix ${it.completed} / ${it.total}" } ?: prefix
    }
    val canOpenDirectories: Boolean get() = (!loading || scanProgress != null || showingCachedContent) &&
        !busy && storageGranted && storageError == null && recoveryError == null
    val loadingMessage: String get() = scanProgress?.let { "正在筛选音频 ${it.completed} / ${it.total}" }
        ?: "正在读取音乐文件夹"
    val processingMessage: String get() = fileProgress?.let {
        "正式处理文件：已完成 ${it.completed}/${it.total}（最多${minOf(it.total, LocalFileWork.parallelism)}个文件任务并行）"
    }
        ?: "正在准备文件任务"
    val renameLoadingMessage: String get() = renameReadProgress?.let {
        if (it.completed == it.total) "正在检查文件名" else "正在读取标签(${it.completed}/${it.total})"
    } ?: "正在准备标签读取"
    val canEditSelection: Boolean get() = canSelectFiles && selected.isNotEmpty()
    val canScrape: Boolean get() = policies.values.any { it.enabled } && canEditSelection
    val renamePattern: String get() = if (renamePreset == RenamePreset.CUSTOM) renameCustomPattern else renamePreset.pattern
    val canRename: Boolean get() = canEditSelection && !renameLoading && renameError == null && renameEntries.any { it.willRename }
    val canSaveTags: Boolean get() = canEditSelection && editor.canSave
}

sealed interface MainEffect {
    data class OpenDirectory(val uri: String) : MainEffect
    data object OpenSearch : MainEffect
    data object CloseSearch : MainEffect
    data object OpenOptions : MainEffect
    data object OpenRename : MainEffect
    data object OpenTagEditor : MainEffect
    data object ChooseTagCover : MainEffect
    data object OpenCandidates : MainEffect
    data object ReturnToBrowser : MainEffect
    data object ResetBrowserRoot : MainEffect
    data object ChooseStorageTree : MainEffect
    data object OpenAbout : MainEffect
    data class OpenUrl(val url: String) : MainEffect
    data class OpenAlbum(val key: String) : MainEffect
}

internal object AboutLinks {
    const val Repository = "https://github.com/Michubil/MusicTag"
    const val License = "$Repository/blob/main/LICENSE"
    const val Notices = "$Repository/blob/main/THIRD_PARTY_NOTICES.md"
}
