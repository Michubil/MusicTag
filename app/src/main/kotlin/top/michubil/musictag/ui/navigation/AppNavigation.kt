package top.michubil.musictag.ui.navigation

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.androidgui.core.designsystem.component.*
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.theme.AppTheme
import top.michubil.musictag.R
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.ui.*

internal object Routes {
    const val Files = "files"
    const val Folder = "files/folder?path={path}"
    const val Search = "files/search"
    const val Options = "files/options"
    const val Rename = "files/rename"
    const val Editor = "files/editor"
    const val Candidates = "files/candidates"
    const val Settings = "settings"
    const val About = "settings/about"
}

internal fun rootRoute(route: String?) = if (route == Routes.Settings || route == Routes.About) Routes.Settings else Routes.Files
internal fun isDirectoryRoute(route: String?) = route == Routes.Files || route == Routes.Folder
internal fun isBrowserRoute(route: String?) = isDirectoryRoute(route) || route == Routes.Search
internal fun shouldLeaveSearch(route: String?) = isDirectoryRoute(route) || rootRoute(route) == Routes.Settings
internal enum class TabDecision { Stay, PopToRoot, SwitchRoot }

internal fun browserDirectoryUri(route: String?, rootUri: String?, folderUri: String?): String? = when (route) {
    Routes.Files -> rootUri
    Routes.Folder -> folderUri
    else -> null
}

@Composable
internal fun BrowserDirectoryEffect(entryId: String?, directoryUri: String?, onDirectoryShown: (String) -> Unit) {
    // The root URI can become available after its navigation entry is created.
    LaunchedEffect(entryId, directoryUri) {
        directoryUri?.let(onDirectoryShown)
    }
}

/** Navigation may survive process death, but selection and matching work deliberately do not. */
internal fun shouldReturnToBrowser(route: String, state: MainUiState): Boolean =
    (route == Routes.Options || route == Routes.Candidates || route == Routes.Rename || route == Routes.Editor) && state.selected.isEmpty() && !state.busy

internal fun tabDecision(current: String, target: String): TabDecision = when {
    current == target -> TabDecision.Stay
    rootRoute(current) == target -> TabDecision.PopToRoot
    else -> TabDecision.SwitchRoot
}

internal fun resolvePageMotion(from: String?, to: String?, isPop: Boolean = false): AppPageMotion {
    if (rootRoute(from) != rootRoute(to)) {
        return if (rootRoute(to) == Routes.Settings) AppPageMotion.TabForward else AppPageMotion.TabBackward
    }
    return if (isPop) AppPageMotion.Close else AppPageMotion.Open
}

@Composable
fun MusicTagApp(
    model: MainViewModel,
    onChooseStorageTree: () -> Unit,
    onChooseTagCover: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val dark = when (state.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    AppTheme(darkTheme = dark, dynamicColor = state.dynamicColor) {
        val snackbar = rememberAppSnackbarState()
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showMessage(it)
                model.onAction(MainAction.ConsumeMessage)
            }
        }
        key(state.treeUri) { AppNavigation(state, model, snackbar, onChooseStorageTree, onChooseTagCover, onOpenUrl) }
    }
}

@Composable
private fun AppNavigation(
    state: MainUiState,
    model: MainViewModel,
    snackbar: AppSnackbarState,
    onChooseStorageTree: () -> Unit,
    onChooseTagCover: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.Files
    val transitions = rememberAppPageTransitions()
    val lifecycle = entry?.lifecycle?.currentStateAsState()?.value
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    val latestDirectoryPicker by rememberUpdatedState(onChooseStorageTree)
    val latestCoverPicker by rememberUpdatedState(onChooseTagCover)
    val latestUrlOpener by rememberUpdatedState(onOpenUrl)
    val browserVisible = isBrowserRoute(route)
    val searchVisible = route == Routes.Search
    val showFileActions = browserVisible && state.canEditSelection
    val settingsVisible = route == Routes.Settings
    val aboutVisible = route == Routes.About
    val settingsTab = rootRoute(route) == Routes.Settings
    val appName = stringResource(R.string.app_name)
    val title = when (route) {
        Routes.Options -> "选择刮削内容"
        Routes.Rename -> "文件名修改"
        Routes.Editor -> "编辑标签"
        Routes.Candidates -> "选择匹配歌曲"
        Routes.About -> "关于"
        else -> appName
    }
    LaunchedEffect(showFileActions) {
        if (!showFileActions) model.onAction(MainAction.SetEditMenu(false))
    }
    BrowserDirectoryEffect(
        entry?.id,
        browserDirectoryUri(entry?.destination?.route, state.root?.uri, entry?.arguments?.getString("path")),
    ) { uri ->
        model.onAction(MainAction.DirectoryShown(uri))
    }
    LaunchedEffect(entry?.id) {
        if (entry != null && route != Routes.Candidates) model.onAction(MainAction.CancelCandidateSearch)
        if (entry != null && route != Routes.Rename) model.onAction(MainAction.CancelRenamePreview)
        if (entry != null && route != Routes.Editor) model.onAction(MainAction.CancelTagEditor)
        model.onAction(MainAction.DismissTransientUi)
    }
    LaunchedEffect(route) {
        if (shouldLeaveSearch(route)) model.onAction(MainAction.LeaveSearch)
    }
    LaunchedEffect(route, state.selected.isEmpty(), state.busy) {
        if (shouldReturnToBrowser(route, state)) {
            nav.popBackStack(if (route == Routes.Editor) Routes.Editor else if (route == Routes.Rename) Routes.Rename else Routes.Options, inclusive = true)
        }
    }
    LaunchedEffect(lifecycle, pendingTab, route) {
        val index = pendingTab
        if (index != null && lifecycle == Lifecycle.State.RESUMED) {
            pendingTab = null
            val target = if (index == 0) Routes.Files else Routes.Settings
            if (tabDecision(route, target) != TabDecision.Stay) {
                model.onAction(MainAction.DismissTransientUi)
                selectTopLevel(nav, route, target)
            }
        }
    }
    LaunchedEffect(model, nav) {
        model.effects.collect { effect ->
            val currentEntry = nav.currentBackStackEntry
            val current = currentEntry?.destination?.route
            when (effect) {
                is MainEffect.OpenDirectory -> if (
                    isDirectoryRoute(current) && currentEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED &&
                    currentEntry.arguments?.getString("path") != effect.uri
                ) {
                    // Each folder needs its own entry for transitions and parent-by-parent back navigation.
                    nav.navigate("files/folder?path=${Uri.encode(effect.uri)}")
                }
                MainEffect.OpenSearch -> if (isDirectoryRoute(current)) nav.navigate(Routes.Search) { launchSingleTop = true }
                MainEffect.CloseSearch -> if (current == Routes.Search) nav.popBackStack()
                MainEffect.OpenOptions -> if (isBrowserRoute(current)) nav.navigate(Routes.Options) { launchSingleTop = true }
                MainEffect.OpenRename -> if (isBrowserRoute(current)) nav.navigate(Routes.Rename) { launchSingleTop = true }
                MainEffect.OpenTagEditor -> if (isBrowserRoute(current)) nav.navigate(Routes.Editor) { launchSingleTop = true }
                MainEffect.ChooseTagCover -> if (current == Routes.Editor) latestCoverPicker()
                MainEffect.OpenCandidates -> if (current == Routes.Options) nav.navigate(Routes.Candidates) { launchSingleTop = true }
                MainEffect.ReturnToBrowser -> if (current == Routes.Options || current == Routes.Candidates || current == Routes.Rename || current == Routes.Editor) {
                    nav.popBackStack(if (current == Routes.Editor) Routes.Editor else if (current == Routes.Rename) Routes.Rename else Routes.Options, inclusive = true)
                }
                MainEffect.ChooseStorageTree -> latestDirectoryPicker()
                MainEffect.OpenAbout -> if (current == Routes.Settings) nav.navigate(Routes.About) { launchSingleTop = true }
                is MainEffect.OpenUrl -> latestUrlOpener(effect.url)
                MainEffect.ResetBrowserRoot -> nav.navigate(Routes.Files) {
                    popUpTo(Routes.Files) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    val visibleItems = state.visibleItems
    val allSelected = visibleItems.isNotEmpty() && visibleItems.all { it.document.uri in state.selected }
    val fileMenu = if (browserVisible) buildList {
        if (!searchVisible) add(AppMenuItem("folder", if (state.treeUri == null) "选择文件夹" else "更换文件夹", AppIcons.Folder, !state.busy && !state.loading))
        add(AppMenuItem("sort", "排序", AppIcons.Sort, state.canSort))
        add(AppMenuItem("all", if (allSelected) "取消全选" else "全选", AppIcons.Check, state.canSelectFiles && visibleItems.isNotEmpty()))
        if (state.selected.isNotEmpty()) add(AppMenuItem("clear", "清除选择", AppIcons.Check))
    } else emptyList()
    AppScaffold(
        screenKey = entry?.id,
        modalVisible = ((state.themeDialog || state.durationDialog || state.pathDialog || state.mp3TagVersionDialog || state.sourceDialog != null) && settingsVisible) || (state.sortDialog && browserVisible) ||
            (state.availableUpdate != null && aboutVisible) || (state.editMenuExpanded && showFileActions),
        topBar = {
            AppTopBar(
                title = title,
                menuItems = fileMenu,
                menuExpanded = state.fileMenuExpanded && browserVisible,
                onMenuExpandedChange = { model.onAction(MainAction.SetFileMenu(it)) },
                onMenuItemClick = { id ->
                    model.onAction(when (id) {
                        "folder" -> MainAction.ChooseStorageTree
                        "sort" -> MainAction.ShowSortDialog
                        "all" -> MainAction.ToggleSelectAll
                        "clear" -> MainAction.ClearSelection
                        else -> error("Unknown file menu action")
                    })
                },
                actions = if (browserVisible && !searchVisible) {
                    listOf(AppMenuItem("search", "搜索", AppIcons.Search, state.canSearch))
                } else emptyList(),
                onActionClick = { if (it == "search") model.onAction(MainAction.OpenSearch) },
                search = if (searchVisible) AppSearchState(state.searchQuery, "歌曲名、艺术家或专辑") else null,
                onSearchQueryChange = { model.onAction(MainAction.SetSearchQuery(it)) },
                onSearchClose = { model.onAction(MainAction.CloseSearch) },
            )
        },
        bottomBar = {
            AppNavigationBar(
                destinations = listOf(
                    AppNavigationDestination("文件", AppIcons.Music),
                    AppNavigationDestination("设置", AppIcons.Settings),
                ),
                selectedIndex = if (settingsTab) 1 else 0,
                onDestinationSelected = { pendingTab = it },
            )
        },
        bottomActions = if (route == Routes.Options) {
            { OptionsActions(state, model::onAction) }
        } else if (route == Routes.Rename) {
            { RenameActions(state, model::onAction) }
        } else if (route == Routes.Editor) {
            { TagEditorActions(state.editor, state.canEditSelection, model::onAction) }
        } else null,
        floatingAction = if (showFileActions) {
            { FileActions(state, model::onAction) }
        } else null,
        snackbarState = snackbar,
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.Files,
            enterTransition = { transitions.enter(resolvePageMotion(initialState.destination.route, targetState.destination.route)) },
            exitTransition = { transitions.exit(resolvePageMotion(initialState.destination.route, targetState.destination.route)) },
            popEnterTransition = { transitions.enter(resolvePageMotion(initialState.destination.route, targetState.destination.route, true)) },
            popExitTransition = { transitions.exit(resolvePageMotion(initialState.destination.route, targetState.destination.route, true)) },
        ) {
            composable(Routes.Search) {
                LaunchedEffect(Unit) { model.onAction(MainAction.ActivateSearch) }
                SearchPage(state, model::onAction)
            }
            composable(Routes.Files) { files ->
                DirectoryPage(state.root?.uri, files.id == entry?.id, state, model::onAction)
            }
            composable(
                Routes.Folder,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { folder ->
                DirectoryPage(requireNotNull(folder.arguments?.getString("path")), folder.id == entry?.id, state, model::onAction)
            }
            composable(Routes.Options) { OptionsPage(state, model::onAction) }
            composable(Routes.Rename) { RenamePage(state, model::onAction) }
            composable(Routes.Editor) { TagEditorPage(state.editor, state.busy, model::onAction) }
            composable(Routes.Candidates) { CandidatesPage(state, model::onAction) }
            composable(Routes.Settings) { SettingsPage(state, model::onAction) }
            composable(Routes.About) { AboutPage(state, model::onAction) }
        }
    }
    MainDialogs(state, settingsVisible, browserVisible, aboutVisible, model::onAction)
}

/** Freeze an outgoing folder while its exit/predictive-back transition is still on screen. */
@Composable
private fun DirectoryPage(uri: String?, active: Boolean, state: MainUiState, onAction: (MainAction) -> Unit) {
    val displayed = rememberDirectoryState(uri, active, state)
    BrowserPage(displayed, onAction, loadPreviews = active && displayed === state)
}

@Composable
internal fun rememberDirectoryState(uri: String?, active: Boolean, state: MainUiState): MainUiState {
    var previous by remember(uri) {
        mutableStateOf(state.copy(directory = null, items = emptyList(), loading = true))
    }
    // Loading the next folder clears shared items before its directory URI changes.
    // Only the current entry may consume those updates; the outgoing page keeps its last frame.
    val live = active && (state.directory?.uri == uri || !state.storageGranted || state.storageError != null)
    val displayed = if (live) state else remember(previous) { previous.freezePreviews() }
    SideEffect { if (live) previous = state }
    return displayed
}

private fun selectTopLevel(nav: NavHostController, current: String, target: String) {
    when (tabDecision(current, target)) {
        TabDecision.Stay -> Unit
        TabDecision.PopToRoot -> nav.popBackStack(target, inclusive = false)
        TabDecision.SwitchRoot -> nav.navigate(target) {
            popUpTo(Routes.Files) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }
}
