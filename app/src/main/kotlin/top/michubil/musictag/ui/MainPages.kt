package top.michubil.musictag.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.asImageBitmap
import dev.androidgui.core.designsystem.component.*
import dev.androidgui.core.designsystem.icon.AppIcons
import top.michubil.musictag.BuildConfig
import top.michubil.musictag.R
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.FileSort
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.Mp3TagVersion
import top.michubil.musictag.data.model.MetadataGroup
import top.michubil.musictag.data.model.SourceOrder
import kotlin.math.roundToInt

@Composable
fun BrowserPage(state: MainUiState, onAction: (MainAction) -> Unit, loadPreviews: Boolean = true) {
    AppRefreshableContentList(
        refreshing = state.refreshing,
        enabled = state.canRefresh,
        onRefresh = { onAction(MainAction.PullRefresh) },
        compact = true,
        status = if (state.busy || (state.loading && !state.showingCachedContent && !state.refreshing)) {
            {
                AppLoadingStatus(
                    message = if (state.busy) state.processingMessage else state.loadingMessage,
                    progress = (if (state.busy) state.fileProgress else state.scanProgress)
                        ?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total },
                    deferDisplay = !state.busy,
                )
            }
        } else null,
    ) {
        if (state.showStoragePicker) {
            item {
                EmptyState(
                    title = "选择音乐文件夹",
                    message = state.storageError ?: "选择文件夹并允许读写，Music Tag 只访问你授权的文件夹及其子目录。",
                    actionLabel = "选择文件夹",
                    onAction = { onAction(MainAction.ChooseStorageTree) },
                )
            }
        } else if (state.storageGranted) {
            state.storageError?.let { error ->
                item {
                    ErrorState(
                        title = "无法刷新文件夹",
                        message = error,
                        actionLabel = "重试",
                        onAction = { onAction(MainAction.Refresh) },
                    )
                }
            }
            state.recoveryError?.let { error ->
                item {
                    ErrorState(
                        title = "有文件需要恢复",
                        message = error,
                        actionLabel = "重试恢复",
                        onAction = { onAction(MainAction.Refresh) },
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
                        onClick = { onAction(MainAction.OpenDirectory(path)) },
                        onSelectedChange = { onAction(MainAction.ToggleSelection(path)) },
                        enabled = state.canOpenDirectories,
                        selectionEnabled = state.canSelectFiles,
                    )
                } else {
                    AudioFileRow(item, state, onAction, loadPreviews)
                }
            }
        }
    }
}

@Composable
fun SearchPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppRefreshableContentList(
        refreshing = state.searchRefreshing,
        enabled = state.canRefreshSearch,
        onRefresh = { onAction(MainAction.PullRefresh) },
        compact = true,
        status = if (state.searchLoading) {
            {
                AppLoadingStatus(
                    message = state.searchLoadingMessage,
                    progress = state.searchProgress?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total },
                    deferDisplay = !state.searchRefreshing,
                )
            }
        } else null,
    ) {
        when {
            state.searchQuery.isBlank() -> item { EmptyState(title = "搜索歌曲名、艺术家或专辑") }
            state.searchItems.isEmpty() && !state.searchLoading && !state.searchRefreshing -> item { EmptyState(title = "没有找到匹配的歌曲") }
            else -> items(state.searchItems, key = { it.document.uri }) { item ->
                AudioFileRow(item, state, onAction, loadPreviews = true)
            }
        }
    }
}

@Composable
private fun AudioFileRow(item: FileItem, state: MainUiState, onAction: (MainAction) -> Unit, loadPreviews: Boolean) {
    val preview by item.preview.collectAsStateWithLifecycle()
    DisposableEffect(item, state.artworkRevision, loadPreviews) {
        if (loadPreviews) onAction(MainAction.LoadFilePreview(item))
        onDispose { if (loadPreviews) onAction(MainAction.ReleaseArtwork(item)) }
    }
    AppThreeLineContentRow(
        title = item.document.name,
        supporting = preview.track?.artists.orEmpty().joinToString(" / "),
        detail = preview.track?.album.orEmpty(),
        icon = AppIcons.Music,
        artwork = preview.artwork?.asImageBitmap(),
        selected = item.document.uri in state.selected,
        selectionLabel = "选择 ${item.document.name}",
        onClick = { onAction(MainAction.ToggleSelection(item.document.uri)) },
        onSelectedChange = { onAction(MainAction.ToggleSelection(item.document.uri)) },
        enabled = state.canSelectFiles,
    )
}

@Composable
fun OptionsActions(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppActionBar(
        primaryLabel = "开始刮削",
        secondaryLabel = "手动匹配",
        onPrimary = { onAction(MainAction.StartAutomatic) },
        onSecondary = { onAction(MainAction.LoadCandidates) },
        enabled = state.canScrape,
    )
}

@Composable
fun OptionsPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    val enabledPolicies = state.policies.values.filter { it.enabled }
    AppContentList {
        if (state.busy) item { AppProgress() }
        item {
            PreferenceGroup {
                item {
                    AppSelectionRow(
                        label = "全选",
                        checked = state.policies.values.all { it.enabled },
                        onCheckedChange = { onAction(MainAction.ToggleAllFields) },
                        secondaryLabel = "全选",
                        secondaryDescription = "覆盖现有值，全选",
                        secondaryChecked = enabledPolicies.isNotEmpty() && enabledPolicies.all { it.overwrite },
                        onSecondaryChange = { onAction(MainAction.ToggleAllOverwrite) },
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
                            onCheckedChange = { onAction(MainAction.SetFieldEnabled(field, it)) },
                            secondaryLabel = "覆盖",
                            secondaryDescription = "覆盖已有${field.label}",
                            secondaryChecked = policy.overwrite,
                            onSecondaryChange = { onAction(MainAction.SetOverwrite(field, it)) },
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
fun CandidatesPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppContentList {
        when {
            state.busy -> item { AppProgress(message = "正在查找匹配歌曲") }
            state.candidateError != null -> item {
                ErrorState(
                    title = "无法获取候选歌曲",
                    message = state.candidateError,
                    actionLabel = "重试",
                    onAction = { onAction(MainAction.LoadCandidates) },
                )
            }
            state.candidates.isEmpty() -> item { EmptyState(title = "没有找到候选歌曲") }
            else -> items(state.candidates, key = { it.candidate.key }) { result ->
                AppContentRow(
                    title = result.candidate.title,
                    summary = result.candidate.artists.joinToString(" / "),
                    details = "${result.candidate.album} · ${result.candidate.source.label} · 匹配度 ${(result.confidence * 100).roundToInt()}%",
                    icon = AppIcons.Music,
                    onClick = { onAction(MainAction.ChooseCandidate(result.candidate)) },
                )
            }
        }
    }
}

@Composable
fun SettingsPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppContentList {
        item {
            PreferenceGroup(title = "刮削数据源") {
                MetadataGroup.entries.forEach { group ->
                    item {
                        SettingChoiceRow(
                            title = group.label,
                            value = state.scrapeSources[group].label,
                            icon = if (group == MetadataGroup.LYRICS) AppIcons.Lyrics else AppIcons.Music,
                            enabled = !state.busy,
                            onClick = { onAction(MainAction.ShowSourceDialog(group)) },
                        )
                    }
                }
            }
        }
        item { AppSupportingText("组合包含标题、艺术家、专辑、日期及编号。按所选顺序查询，前一个源缺少内容时再尝试下一个。") }
        item {
            PreferenceGroup(title = "音频过滤") {
                item {
                    SettingChoiceRow(
                        title = "按时间长短", value = if (state.audioFilters.minimumSeconds == 0) "未设置" else "隐藏短于 ${state.audioFilters.minimumSeconds} 秒的音频",
                        icon = AppIcons.Music, enabled = !state.busy,
                        onClick = { onAction(MainAction.ShowDurationFilter) },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "按文件夹路径", value = if (state.audioFilters.excludedPaths.isEmpty()) "未设置" else "排除 ${state.audioFilters.excludedPaths.size} 个目录及其子目录",
                        icon = AppIcons.Folder, enabled = !state.busy,
                        onClick = { onAction(MainAction.ShowPathFilter) },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "文件操作") {
                item {
                    SettingChoiceRow(
                        title = "MP3 标签写入版本",
                        value = state.mp3TagVersion.label,
                        icon = AppIcons.Music,
                        enabled = !state.busy,
                        onClick = { onAction(MainAction.ShowMp3TagVersionDialog) },
                    )
                }
                item {
                    SettingSwitchRow(
                        title = "递归包含子文件夹",
                        summary = "对所选文件夹执行操作时，同时处理其子文件夹中的音乐文件",
                        icon = AppIcons.Folder,
                        checked = state.recursive,
                        enabled = !state.busy,
                        onCheckedChange = { onAction(MainAction.SetRecursive(it)) },
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
                        onClick = { onAction(MainAction.ShowThemeDialog) },
                    )
                }
                item {
                    SettingSwitchRow(
                        title = "系统动态配色",
                        summary = "使用系统壁纸生成应用配色",
                        icon = AppIcons.Appearance,
                        checked = state.dynamicColor,
                        onCheckedChange = { onAction(MainAction.SetDynamicColor(it)) },
                    )
                }
            }
        }
        item {
            PreferenceGroup(title = "歌词下载") {
                item {
                    SettingSwitchRow(
                        title = "格式化时间轴",
                        summary = "三位毫秒转二位毫秒",
                        icon = AppIcons.Lyrics,
                        checked = state.formatLyricsTimeline,
                        onCheckedChange = { onAction(MainAction.SetFormatLyricsTimeline(it)) },
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
                        onClick = { onAction(MainAction.ShowAbout) },
                    )
                }
            }
        }
    }
}

@Composable
fun AboutPage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppContentList {
        item {
            AppIdentityHeader(
                icon = R.drawable.ic_app,
                name = "Music Tag",
                summary = "本地音乐标签工具",
                version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                actionLabel = if (state.checkingUpdate) "正在检查更新" else "检查更新",
                actionEnabled = !state.checkingUpdate,
                onAction = { onAction(MainAction.CheckUpdates) },
            )
        }
        item {
            PreferenceGroup(title = "开源相关") {
                item {
                    SettingChoiceRow(
                        title = "源代码",
                        value = "GitHub",
                        icon = AppIcons.About,
                        onClick = { onAction(MainAction.OpenSourceRepository) },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "许可证",
                        value = "Apache 2.0",
                        icon = AppIcons.About,
                        onClick = { onAction(MainAction.OpenLicense) },
                    )
                }
                item {
                    SettingChoiceRow(
                        title = "第三方声明",
                        value = "依赖与参考实现",
                        icon = AppIcons.About,
                        onClick = { onAction(MainAction.OpenNotices) },
                    )
                }
            }
        }
    }
}

@Composable
fun MainDialogs(state: MainUiState, settingsVisible: Boolean, browserVisible: Boolean, aboutVisible: Boolean, onAction: (MainAction) -> Unit) {
    MetadataGroup.entries.forEach { group ->
        AppSingleChoiceDialog(
            visible = state.sourceDialog == group && settingsVisible,
            title = group.label,
            options = SourceOrder.entries.map { AppChoiceOption(it, it.label) },
            selectedValue = state.scrapeSources[group],
            enabled = !state.busy,
            onSelect = { onAction(MainAction.SetSourceOrder(group, it)) },
            onDismissRequest = { onAction(MainAction.DismissSourceDialog) },
        )
    }
    AppSingleChoiceDialog(
        visible = state.mp3TagVersionDialog && settingsVisible,
        title = "MP3 标签写入版本",
        options = Mp3TagVersion.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = state.mp3TagVersion,
        enabled = !state.busy,
        onSelect = { onAction(MainAction.SetMp3TagVersion(it)) },
        onDismissRequest = { onAction(MainAction.DismissMp3TagVersionDialog) },
    )
    AppSingleChoiceDialog(
        visible = state.durationDialog && settingsVisible, title = "隐藏短于所选时长的音频",
        options = AudioFilters.minimumSecondsOptions.map { AppChoiceOption(it, if (it == 0) "未设置" else "$it 秒") },
        selectedValue = state.audioFilters.minimumSeconds, enabled = !state.busy,
        onSelect = { onAction(MainAction.SetDurationFilter(it)) },
        onDismissRequest = { onAction(MainAction.DismissDurationFilter) },
    )
    AppTextInputDialog(
        visible = state.pathDialog && settingsVisible, title = "排除文件夹路径", value = state.pathDraft,
        label = "相对路径，一行一个", description = "相对于所选音乐文件夹，例如 播客/缓存。排除目录及其子目录；留空取消过滤。过滤同时作用于文件列表和所有批量操作。",
        error = state.pathError, enabled = !state.busy,
        onValueChange = { onAction(MainAction.SetPathDraft(it)) },
        onConfirm = { onAction(MainAction.ApplyPathFilter) },
        onDismissRequest = { onAction(MainAction.DismissPathFilter) },
    )
    AppSingleChoiceDialog(
        visible = state.themeDialog && settingsVisible,
        title = "主题",
        options = ThemeMode.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = state.themeMode,
        onSelect = { onAction(MainAction.SetTheme(it)) },
        onDismissRequest = { onAction(MainAction.DismissThemeDialog) },
    )
    AppSingleChoiceDialog(
        visible = state.sortDialog && browserVisible,
        title = "排序",
        options = FileSort.entries.map { AppChoiceOption(it, it.label) },
        selectedValue = state.sortDraft,
        enabled = state.canSort,
        onSelect = { onAction(MainAction.SetSortDraft(it)) },
        onDismissRequest = { onAction(MainAction.DismissSortDialog) },
        toggle = AppDialogToggle("倒序", state.sortDescendingDraft) { onAction(MainAction.SetSortDescendingDraft(it)) },
        actions = AppDialogActions("确定", "取消") { onAction(MainAction.ApplySort) },
    )
    AppConfirmDialog(
        visible = state.availableUpdate != null && aboutVisible,
        title = "发现新版本",
        message = state.availableUpdate?.let { "${it.versionName} 可下载，是否更新？" }.orEmpty(),
        actions = AppDialogActions("下载", "取消") { onAction(MainAction.ConfirmUpdateDownload) },
        onDismissRequest = { onAction(MainAction.DismissUpdateDownload) },
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
