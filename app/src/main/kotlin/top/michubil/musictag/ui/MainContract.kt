package top.michubil.musictag.ui

import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.ScanProgress
import top.michubil.musictag.data.FileSort
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.data.model.FieldPolicy
import top.michubil.musictag.data.model.MatchResult
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.ScrapeSources
import top.michubil.musictag.data.model.SourceOrder
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.rename.RenameEntry
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.AlbumGroup
import top.michubil.musictag.data.AppRelease
import top.michubil.musictag.data.AlbumSort

data class MainUiState(
    val treeUri: String? = null,
    val root: MusicDocument? = null,
    val directory: MusicDocument? = null,
    val items: List<FileItem> = emptyList(),
    val artworkRevision: Int = 0,
    val selected: Set<String> = emptySet(),
    val editMenuExpanded: Boolean = false,
    val editor: TagEditorState = TagEditorState(),
    val audioFilters: AudioFilters = AudioFilters(),
    val durationDialog: Boolean = false,
    val pathDialog: Boolean = false,
    val pathDraft: String = "",
    val pathError: String? = null,
    val renamePreset: RenamePreset = RenamePreset.TITLE_ARTIST,
    val renameCustomPattern: String = "@1-@2",
    val renameLoading: Boolean = false,
    val renameReadProgress: ScanProgress? = null,
    val renameEntries: List<RenameEntry> = emptyList(),
    val renameError: String? = null,
    val policies: Map<MetadataField, FieldPolicy> = MetadataField.entries.associateWith { FieldPolicy() },
    val recursive: Boolean = false,
    val candidates: List<MatchResult> = emptyList(),
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
    val themeDialog: Boolean = false,
    val sortDialog: Boolean = false,
    val fileMenuExpanded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val fileSort: FileSort = FileSort.NAME,
    val sortDescending: Boolean = false,
    val sortDraft: FileSort = FileSort.NAME,
    val sortDescendingDraft: Boolean = false,
    val formatLyricsTimeline: Boolean = true,
    val mp3TagVersion: Mp3TagVersion = Mp3TagVersion.V24,
    val mp3TagVersionDialog: Boolean = false,
    val scrapeSources: ScrapeSources = ScrapeSources(),
    val sourceDialog: MetadataGroup? = null,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchItems: List<FileItem> = emptyList(),
    val libraryLoading: Boolean = false,
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
    val albumMinColumns: Int = 3,
    val albumSortDialog: Boolean = false,
    val albumColumnsDialog: Boolean = false,
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
        searching -> true
        viewingAlbum -> !libraryLoading
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
    val processingMessage: String get() = fileProgress?.let { "正式处理文件(${it.completed}/${it.total})" }
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

sealed interface MainAction {
    data object Refresh : MainAction
    data object PullRefresh : MainAction
    data object OpenSearch : MainAction
    data object ActivateSearch : MainAction
    data object CloseSearch : MainAction
    data object LeaveSearch : MainAction
    data class SetSearchQuery(val query: String) : MainAction
    data class DirectoryShown(val uri: String) : MainAction
    data class OpenDirectory(val uri: String) : MainAction
    data class ToggleSelection(val uri: String) : MainAction
    data object ToggleSelectAll : MainAction
    data object ClearSelection : MainAction
    data object ShowOptions : MainAction
    data class SetEditMenu(val expanded: Boolean) : MainAction
    data object ShowRename : MainAction
    data object ShowTagEditor : MainAction
    data object ReloadTags : MainAction
    data object CancelTagEditor : MainAction
    data object SaveTags : MainAction
    data class SetTagText(val field: MetadataField, val value: String) : MainAction
    data class SetTagSelected(val field: MetadataField, val selected: Boolean) : MainAction
    data object ChooseTagCover : MainAction
    data class TagCoverSelected(val uri: String) : MainAction
    data object RemoveTagCover : MainAction
    data object ShowDurationFilter : MainAction
    data object DismissDurationFilter : MainAction
    data class SetDurationFilter(val seconds: Int) : MainAction
    data object ShowPathFilter : MainAction
    data object DismissPathFilter : MainAction
    data class SetPathDraft(val text: String) : MainAction
    data object ApplyPathFilter : MainAction
    data class SetRenamePreset(val preset: RenamePreset) : MainAction
    data class SetRenamePattern(val pattern: String) : MainAction
    data object ReloadRename : MainAction
    data object CancelRenamePreview : MainAction
    data object StartRenaming : MainAction
    data class SetFieldEnabled(val field: MetadataField, val enabled: Boolean) : MainAction
    data class SetOverwrite(val field: MetadataField, val enabled: Boolean) : MainAction
    data object ToggleAllFields : MainAction
    data object ToggleAllOverwrite : MainAction
    data class SetRecursive(val enabled: Boolean) : MainAction
    data object StartAutomatic : MainAction
    data object LoadCandidates : MainAction
    data object CancelCandidateSearch : MainAction
    data class ChooseCandidate(val candidate: SongCandidate) : MainAction
    data object DismissTransientUi : MainAction
    data object ConsumeMessage : MainAction
    data object ShowThemeDialog : MainAction
    data object DismissThemeDialog : MainAction
    data class SetTheme(val mode: ThemeMode) : MainAction
    data class SetDynamicColor(val enabled: Boolean) : MainAction
    data class SetFormatLyricsTimeline(val enabled: Boolean) : MainAction
    data object ShowMp3TagVersionDialog : MainAction
    data object DismissMp3TagVersionDialog : MainAction
    data class SetMp3TagVersion(val version: Mp3TagVersion) : MainAction
    data class ShowSourceDialog(val group: MetadataGroup) : MainAction
    data object DismissSourceDialog : MainAction
    data class SetSourceOrder(val group: MetadataGroup, val order: SourceOrder) : MainAction
    data class SetFileMenu(val expanded: Boolean) : MainAction
    data object ShowSortDialog : MainAction
    data class SetSortDraft(val sort: FileSort) : MainAction
    data class SetSortDescendingDraft(val descending: Boolean) : MainAction
    data object DismissSortDialog : MainAction
    data object ApplySort : MainAction
    data object ChooseStorageTree : MainAction
    data class StorageTreeSelected(val uri: String, val grantFlags: Int) : MainAction
    data class LoadFilePreview(val item: FileItem) : MainAction
    data class ReleaseArtwork(val item: FileItem) : MainAction
    data object ShowAbout : MainAction
    data object CheckUpdates : MainAction
    data object ConfirmUpdateDownload : MainAction
    data object DismissUpdateDownload : MainAction
    data object OpenSourceRepository : MainAction
    data object OpenLicense : MainAction
    data object OpenNotices : MainAction
    data class ShowMessage(val message: String) : MainAction
    data object AlbumsShown : MainAction
    data object RebuildLibrary : MainAction
    data class OpenAlbum(val key: String) : MainAction
    data object LeaveAlbum : MainAction
    data object ShowAlbumSortDialog : MainAction
    data object DismissAlbumSortDialog : MainAction
    data class SetAlbumSort(val sort: AlbumSort) : MainAction
    data object ShowAlbumColumnsDialog : MainAction
    data object DismissAlbumColumnsDialog : MainAction
    data class SetAlbumMinColumns(val columns: Int) : MainAction
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
