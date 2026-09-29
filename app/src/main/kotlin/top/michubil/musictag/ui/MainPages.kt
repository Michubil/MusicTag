package top.michubil.musictag.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.androidgui.core.designsystem.component.*
import dev.androidgui.core.designsystem.icon.AppIcons
import top.michubil.musictag.BuildConfig
import top.michubil.musictag.R
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.AlbumSort
import top.michubil.musictag.data.FileSort
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MusicSource

@Composable
fun BrowserPage(state: MainUiState, model: MainViewModel, loadPreviews: Boolean = true) {
    AppRefreshableContentList(
        refreshing = state.refreshing,
        enabled = state.canRefresh,
        onRefresh = { model.pullRefresh() },
        compact = true,
        status = if (state.busy || (state.loading && !state.showingCachedContent && !state.refreshing)) {
            {
                AppLoadingStatus(
                    message = if (state.busy) state.processingMessage else state.loadingMessage,
                    progress = (if (state.busy) state.fileProgress else state.scanProgress)?.fraction,
                    deferDisplay = !state.busy,
                )
            }
        } else null,
    ) {
        if (state.showStoragePicker) {
            item { SelectMusicFolderEmpty(state, model) }
        } else if (state.storageGranted) {
            state.storageError?.let { error ->
                item {
                    ErrorState(
                        title = "无法刷新文件夹",
                        message = error,
                        actionLabel = "重试",
                        onAction = { model.refresh() },
                    )
                }
            }
            state.recoveryError?.let { error ->
                item {
                    ErrorState(
                        title = "有文件需要恢复",
                        message = error,
                        actionLabel = "重试恢复",
                        onAction = { model.refresh() },
                    )
                }
            }
            if ((!state.loading || state.showingCachedContent) && state.storageError == null && state.items.isEmpty()) {
                item { EmptyState(title = "这里没有文件夹或 FLAC/MP3/WAV 文件") }
            }
            items(state.items, key = { it.document.uri }, contentType = { it.document.isDirectory }) { item ->
                val path = item.document.uri
                if (item.document.isDirectory) {
                    AppContentRow(
                        title = item.document.name,
                        summary = "文件夹",
                        icon = AppIcons.Folder,
                        selected = path in state.selected,
                        selectionLabel = "选择 ${item.document.name}",
                        onClick = { model.openDirectory(path) },
                        onSelectedChange = { model.toggleSelection(path) },
                        enabled = state.canOpenDirectories,
                        selectionEnabled = state.canSelectFiles,
                    )
                } else {
                    AudioFileRow(item, state, model, loadPreviews)
                }
            }
        }
    }
}

@Composable
fun SearchPage(state: MainUiState, model: MainViewModel) {
    AppRefreshableContentList(
        refreshing = state.libraryRefreshing,
        enabled = state.canRefreshSearch,
        onRefresh = { model.pullRefresh() },
        compact = true,
        status = if (state.busy || state.libraryLoading) {
            {
                AppLoadingStatus(
                    message = if (state.busy) state.processingMessage else state.libraryLoadingMessage,
                    progress = (if (state.busy) state.fileProgress else state.libraryProgress)?.fraction,
                    deferDisplay = !state.busy && !state.libraryRefreshing,
                )
            }
        } else null,
    ) {
        when {
            state.searchQuery.isBlank() -> item { EmptyState(title = "搜索歌曲名、艺术家或专辑") }
            state.searchItems.isEmpty() && !state.busy && !state.libraryLoading && !state.libraryRefreshing ->
                item { EmptyState(title = "没有找到匹配的歌曲") }
            else -> items(state.searchItems, key = { it.document.uri }) { item ->
                AudioFileRow(item, state, model, loadPreviews = true)
            }
        }
    }
}

@Composable
internal fun SelectMusicFolderEmpty(state: MainUiState, model: MainViewModel) {
    EmptyState(
        title = "选择音乐文件夹",
        message = state.storageError ?: "选择文件夹并允许读写，Music Tag 只访问你授权的文件夹及其子目录。",
        actionLabel = "选择文件夹",
        onAction = { model.chooseStorageTree() },
    )
}

@Composable
internal fun AudioFileRow(item: FileItem, state: MainUiState, model: MainViewModel, loadPreviews: Boolean) {
    val preview = item.observePreview(model, state.artworkRevision, loadPreviews)
    AppThreeLineContentRow(
        title = item.document.name,
        supporting = preview.track?.artists.orEmpty().joinToString(" / "),
        detail = preview.track?.album.orEmpty(),
        icon = AppIcons.Music,
        artwork = preview.artwork?.asImageBitmap(),
        selected = item.document.uri in state.selected,
        selectionLabel = "选择 ${item.document.name}",
        onClick = { model.toggleSelection(item.document.uri) },
        onSelectedChange = { model.toggleSelection(item.document.uri) },
        enabled = state.canSelectFiles,
    )
}

@Composable
fun OptionsActions(state: MainUiState, model: MainViewModel) {
    AppActionBar(
        primaryLabel = "开始刮削",
        secondaryLabel = "手动匹配",
        onPrimary = { model.startAutomatic() },
        onSecondary = { model.loadCandidates() },
        enabled = state.canScrape,
    )
}

@Composable
fun OptionsPage(state: MainUiState, model: MainViewModel) {
    val enabledPolicies = state.policies.values.filter { it.enabled }
    AppContentList {
        if (state.busy) item { AppProgress(message = state.processingMessage) }
        item {
            PreferenceGroup {
                item {
                    AppSelectionRow(
                        label = "全选",
                        checked = state.policies.values.all { it.enabled },
                        onCheckedChange = { model.toggleAllFields() },
                        secondaryLabel = "全选",
                        secondaryDescription = "覆盖现有值，全选",
                        secondaryChecked = enabledPolicies.isNotEmpty() && enabledPolicies.all { it.overwrite },
                        onSecondaryChange = { model.toggleAllOverwrite() },
                        enabled = !state.busy,
                        secondaryEnabled = enabledPolicies.isNotEmpty(),
                    )
                }
                MetadataField.entries.forEach { field ->
                    item {
                        val policy = state.policies.getValue(field)
                        AppSelectionRow(
                            label = field.label,
                            checked = policy.enabled,
                            onCheckedChange = { model.setFieldEnabled(field, it) },
                            secondaryLabel = "覆盖",
                            secondaryDescription = "覆盖已有${field.label}",
                            secondaryChecked = policy.overwrite,
                            onSecondaryChange = { model.setOverwrite(field, it) },
                            enabled = !state.busy,
                            secondaryEnabled = policy.enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CandidatesPage(state: MainUiState, model: MainViewModel) {
    AppContentList {
        state.candidateSearch.notice?.let { notice -> item { AppSupportingText(notice) } }
        when {
            state.busy -> item { AppProgress(message = "正在查找匹配歌曲") }
            state.candidateSearch.error != null -> item {
                ErrorState(
                    title = "无法获取候选歌曲",
                    message = state.candidateSearch.error,
                    actionLabel = "重试",
                    onAction = { model.loadCandidates() },
                )
            }
            state.candidateSearch.items.isEmpty() -> item {
                EmptyState(title = "没有找到候选歌曲")
            }
            else -> items(state.candidateSearch.items, key = { it.key }) { candidate ->
                val artwork by produceState<ImageBitmap?>(null, candidate.key,
                    candidate.albumId, candidate.coverUrl) {
                    value = model.candidateArtwork(candidate)?.asImageBitmap()
                }
                AppContentRow(
                    title = candidate.title,
                    summary = candidate.artists.joinToString(" / ").ifEmpty { "艺术家未知" },
                    details = listOf(candidate.album, candidate.source.label)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    icon = AppIcons.Music,
                    artwork = artwork,
                    onClick = { model.chooseCandidate(candidate) },
                )
            }
        }
    }
}

@Composable
fun SettingsPage(state: MainUiState, model: MainViewModel) {
    AppContentList {
        item {
            PreferenceGroup(title = "网络源") {
                MusicSource.entries.forEach { source ->
                    item {
                        SettingSwitchRow(
                            title = source.label,
                            icon = AppIcons.Music,
                            checked = source in state.scrapeSources.enabled(),
                            enabled = !state.busy,
                            onCheckedChange = { model.setSourceEnabled(source, it) },
                        )
                    }
                }
            }
        }
        item {
            PreferenceGroup(title = "音频过滤") {
                item {
                    SettingChoiceRow(
                        title = "按时间长短", value = if (state.audioFilters.minimumSeconds == 0) "未设置" else "隐藏短于 ${state.audioFilters.minimumSeconds} 秒的音频",
                        icon = AppIcons.Music, enabled = !state.busy,
                        onClick = { model.showDurationFilter() },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "按文件夹路径", value = if (state.audioFilters.excludedPaths.isEmpty()) "未设置" else "排除 ${state.audioFilters.excludedPaths.size} 个目录及其子目录",
                        icon = AppIcons.Folder, enabled = !state.busy,
                        onClick = { model.showPathFilter() },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "文件操作") {
                item {
                    SettingSwitchRow(
                        title = "包含子文件夹",
                        summary = "对所选文件夹执行操作时，同时处理其子文件夹中的音乐文件",
                        icon = AppIcons.Folder,
                        checked = state.recursive,
                        enabled = !state.busy,
                        onCheckedChange = { model.setRecursive(it) },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "外观") {
                item {
                    SettingChoiceRow(
                        title = "主题",
                        value = state.themeMode.label,
                        icon = AppIcons.Appearance,
                        onClick = { model.showThemeDialog() },
                    )
                }
                item {
                    SettingSwitchRow(
                        title = "系统动态配色",
                        summary = "使用系统壁纸生成应用配色",
                        icon = AppIcons.Appearance,
                        checked = state.dynamicColor,
                        onCheckedChange = { model.setDynamicColor(it) },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "歌词下载") {
                item {
                    SettingSwitchRow(
                        title = "格式化时间轴",
                        summary = "歌词时间保留两位小数",
                        icon = AppIcons.Lyrics,
                        checked = state.formatLyricsTimeline,
                        onCheckedChange = { model.setFormatLyricsTimeline(it) },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "关于") {
                item {
                    SettingChoiceRow(
                        title = "Music Tag",
                        value = "版本 ${BuildConfig.VERSION_NAME}",
                        icon = AppIcons.About,
                        onClick = { model.showAbout() },
                    )
                }
            }
        }
    }
}

@Composable
fun AboutPage(state: MainUiState, model: MainViewModel) {
    AppContentList {
        item {
            AppIdentityHeader(
                icon = R.drawable.ic_app,
                name = "Music Tag",
                summary = "本地音乐标签工具",
                version = BuildConfig.VERSION_NAME,
                actionLabel = if (state.checkingUpdate) "正在检查更新" else "检查更新",
                actionEnabled = !state.checkingUpdate,
                onAction = { model.checkUpdates() },
            )
        }
        item {
            PreferenceGroup(title = "开源相关") {
                item {
                    SettingChoiceRow(
                        title = "源代码",
                        value = "GitHub",
                        icon = AppIcons.About,
                        onClick = { model.openSourceRepository() },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "许可证",
                        value = "Apache 2.0",
                        icon = AppIcons.About,
                        onClick = { model.openLicense() },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "第三方声明",
                        value = "依赖与参考实现",
                        icon = AppIcons.About,
                        onClick = { model.openNotices() },
                    )
                }
            }
        }
    }
}

@Composable
fun MainDialogs(state: MainUiState, visibleDialog: MainDialog?, showUpdate: Boolean, model: MainViewModel) {
    val pathVisible = visibleDialog == MainDialog.PATH
    val sortVisible = visibleDialog == MainDialog.FILE_SORT
    var pathDraft by remember(pathVisible) {
        mutableStateOf(if (pathVisible) state.audioFilters.excludedPaths.joinToString("\n") else "")
    }
    val pathError = runCatching { AudioFilters.parsePaths(pathDraft) }.exceptionOrNull()?.userMessage()
    var sortDraft by remember(sortVisible) { mutableStateOf(state.fileSort) }
    var sortDescendingDraft by remember(sortVisible) { mutableStateOf(state.sortDescending) }
    AppSingleChoiceDialog(
        visible = visibleDialog == MainDialog.DURATION, title = "隐藏短于所选时长的音频",
        options = AudioFilters.minimumSecondsOptions.map { AppChoiceOption(it, if (it == 0) "未设置" else "$it 秒") },
        selectedValue = state.audioFilters.minimumSeconds, enabled = !state.busy,
        onSelect = { model.setDurationFilter(it) },
        onDismissRequest = model::dismissDialog,
    )
    AppTextInputDialog(
        visible = visibleDialog == MainDialog.PATH, title = "排除文件夹路径", value = pathDraft,
        label = "相对路径，一行一个", description = "相对于所选音乐文件夹，例如 播客/缓存。排除目录及其子目录；留空取消过滤。过滤同时作用于文件列表和所有批量操作。",
        error = pathError, enabled = !state.busy,
        onValueChange = { pathDraft = it },
        onConfirm = { if (pathError == null) model.applyPathFilter(pathDraft) },
        onDismissRequest = model::dismissDialog,
    )
    AppSingleChoiceDialog(
        visible = visibleDialog == MainDialog.THEME,
        title = "主题",
        options = ThemeMode.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = state.themeMode,
        onSelect = { model.setTheme(it) },
        onDismissRequest = model::dismissDialog,
    )
    AppSingleChoiceDialog(
        visible = visibleDialog == MainDialog.FILE_SORT,
        title = "排序",
        options = FileSort.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = sortDraft,
        enabled = state.canSort,
        onSelect = { sortDraft = it },
        onDismissRequest = model::dismissDialog,
        toggle = AppDialogToggle("倒序", sortDescendingDraft) { sortDescendingDraft = it },
        actions = AppDialogActions("确定", "取消") { model.applySort(sortDraft, sortDescendingDraft) },
    )
    AppSingleChoiceDialog(
        visible = visibleDialog == MainDialog.ALBUM_SORT,
        title = "排序",
        options = AlbumSort.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = state.albumSort,
        onSelect = { model.setAlbumSort(it) },
        onDismissRequest = model::dismissDialog,
    )
    AppSingleChoiceDialog(
        visible = visibleDialog == MainDialog.ALBUM_COLUMNS,
        title = "最小列数",
        options = listOf(2, 3, 4).map { AppChoiceOption(it, it.toString()) },
        selectedValue = state.albumMinColumns,
        onSelect = { model.setAlbumMinColumns(it) },
        onDismissRequest = model::dismissDialog,
    )
    AppConfirmDialog(
        visible = showUpdate,
        title = "发现新版本",
        message = state.availableUpdate?.let { "${it.versionName} 可下载，是否更新？" }.orEmpty(),
        actions = AppDialogActions("下载", "取消") { model.confirmUpdateDownload() },
        onDismissRequest = { model.dismissUpdateDownload() },
    )
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private val FileSort.label: String
    get() = when (this) {
        FileSort.NAME -> "文件名"
        FileSort.TYPE -> "类型"
        FileSort.MODIFIED -> "修改时间"
    }
