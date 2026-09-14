package dev.androidgui.core.designsystem.component

import android.graphics.Bitmap
import android.view.Window
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.androidgui.core.designsystem.R
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AppContentInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun batchArtworkEditorOmitsImageAndPlaceholderButKeepsActions() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent {
            AppTheme(false, false) {
                AppArtworkEditor(
                    artwork = bitmap, title = "批量编辑", subtitle = "未修改时保留各文件的原封面", placeholder = "暂无封面",
                    selected = false, enabled = true, showArtwork = false,
                    onSelectedChange = {}, onChoose = {}, onRemove = {},
                )
            }
        }
        compose.onNodeWithContentDescription("歌曲封面").assertDoesNotExist()
        compose.onNodeWithText("暂无封面").assertDoesNotExist()
        compose.onNodeWithText("选择封面").assertIsDisplayed()
        compose.onNodeWithText("移除封面").assertIsDisplayed()
    }

    @Test
    fun artworkEditorKeepsGeometryAndExposesCoverActions() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).asImageBitmap()
        val artwork = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(bitmap)
        val selected = mutableStateOf(false)
        var choices = 0
        compose.setContent {
            AppTheme(false, false) {
                AppContentList {
                    item {
                        AppArtworkEditor(
                            artwork = artwork.value, title = "歌曲标题", subtitle = "预览：song.flac", placeholder = "暂无封面",
                            selected = selected.value, enabled = true,
                            onSelectedChange = { selected.value = it }, onChoose = { choices++ },
                            onRemove = { artwork.value = null; selected.value = true },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("歌曲封面").assertIsDisplayed()
        val before = compose.onNodeWithText("歌曲标题").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("选择封面").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
        compose.onNodeWithText("移除封面").performClick()
        compose.onNodeWithText("暂无封面").assertIsDisplayed()
        compose.onNodeWithText("修改封面").assertIsOn()
        val after = compose.onNodeWithText("歌曲标题").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.1f)
    }

    @Test
    fun editableFieldsExposeIndependentApplySelectionAndMultilineInput() {
        val text = mutableStateOf("甲")
        val selected = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        compose.setContent {
            AppTheme(false, false) {
                AppEditableField(
                    label = "艺术家", value = text.value, selected = selected.value, enabled = enabled.value,
                    hint = "每行一个艺术家", kind = AppTextInputKind.ShortMultiline,
                    onSelectedChange = { selected.value = it }, onValueChange = { text.value = it },
                )
            }
        }
        compose.onNodeWithContentDescription("修改艺术家").performClick().assertIsOn()
        compose.onNodeWithText("甲").performTextInput("\n乙")
        compose.runOnIdle {
            assertTrue(text.value.contains('\n'))
            enabled.value = false
        }
        compose.onNodeWithContentDescription("修改艺术家").assertIsNotEnabled()
    }

    @Test
    fun activityAndModalWindowsFollowTheDisplayedTheme() {
        val dark = mutableStateOf(false)
        var dialogWindow: Window? = null
        compose.setContent {
            AppTheme(darkTheme = dark.value, dynamicColor = false) {
                AppModalWindow(true, {}) { modifier ->
                    val view = LocalView.current
                    SideEffect { dialogWindow = (view.parent as? DialogWindowProvider)?.window }
                    Text("Modal", modifier)
                }
            }
        }
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        fun assertAppearance(expected: Int) = compose.runOnIdle {
            assertNotNull(dialogWindow)
            assertEquals(expected, compose.activity.window.insetsController!!.systemBarsAppearance and mask)
            assertEquals(expected, dialogWindow!!.insetsController!!.systemBarsAppearance and mask)
        }
        assertAppearance(mask)
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        assertAppearance(0)
        compose.runOnIdle { dark.value = false }
        compose.waitForIdle()
        assertAppearance(mask)
    }

    @Test
    fun groupHeadingAndLargeTextSwitchKeepSingleAction() {
        val checked = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme(false, false) {
                    AppContentList {
                        item {
                            PreferenceGroup(title = "设置分组") {
                                item {
                                    SettingSwitchRow(
                                        title = "使用系统调色板生成的动态配色", summary = "这段说明应换行并保留完整操作区域。",
                                        icon = AppIcons.Appearance, checked = checked.value,
                                        onCheckedChange = { calls++; checked.value = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("设置分组").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable()).performClick().assertIsOn()
        assertEquals(1, calls)
    }

    @Test
    fun asyncThreeLineContentKeepsItsHeightAtNormalAndDoubleFontScale() {
        val loaded = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                AppTheme(false, false) {
                    Box(Modifier.testTag("row")) {
                        AppThreeLineContentRow(
                            title = "Very long 文件名 ".repeat(10),
                            supporting = if (loaded.value) "艺术家 Artists ".repeat(20) else "",
                            detail = if (loaded.value) "专辑 Album ".repeat(20) else "",
                            icon = AppIcons.Empty,
                            artwork = if (loaded.value) Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap() else null,
                            selected = false, selectionLabel = "Select file", onClick = {}, onSelectedChange = {},
                        )
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { loaded.value = false; fontScale.value = scale }
            val before = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { loaded.value = true }
            val after = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
            assertEquals(before, after)
            compose.onNodeWithContentDescription("Select file").assertIsDisplayed()
        }
    }

    @Test
    fun lastRowClearsPinnedActionsAndNavigationWithoutViewportReflow() {
        compose.setContent {
            AppTheme(false, false) {
                AppScaffold(
                    bottomActions = { AppActionBar("Save", "Reload", {}, {}, enabled = false, secondaryEnabled = true) },
                    bottomBar = {
                        AppNavigationBar(listOf(AppNavigationDestination("Files", AppIcons.Empty)), 0, {})
                    },
                ) {
                    AppContentList(Modifier.testTag("list")) {
                        items(40) { Text("Row $it", Modifier.height(64.dp)) }
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        compose.onNodeWithText("Row 39").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Reload").assertIsDisplayed()
        compose.onNodeWithContentDescription("Files").assertIsDisplayed()
        val last = compose.onNodeWithText("Row 39").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom <= save.top)
        assertEquals(before, compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun lastSelectionCanScrollAboveTheFloatingAction() {
        compose.setContent {
            AppTheme(false, false) {
                AppScaffold(
                    floatingAction = { AppFloatingAction("Actions", AppIcons.Edit, {}) },
                    bottomBar = { AppNavigationBar(listOf(AppNavigationDestination("Files", AppIcons.Empty)), 0, {}) },
                ) {
                    AppContentList {
                        items(40) { Text("Row $it", Modifier.height(64.dp)) }
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        compose.onNodeWithText("Row 39").assertIsDisplayed()
        val last = compose.onNodeWithText("Row 39").fetchSemanticsNode().boundsInRoot
        val fab = compose.onNodeWithContentDescription("Actions").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom <= fab.top)
    }

    @Test
    fun draftChoiceCancelDoesNotCommitAndConfirmCommitsOnce() {
        val visible = mutableStateOf(true)
        val selected = mutableStateOf("name")
        val reverse = mutableStateOf(false)
        var commits = 0
        compose.setContent {
            AppTheme(false, false) {
                AppSingleChoiceDialog(
                    visible = visible.value, title = "Sort", selectedValue = selected.value,
                    options = listOf(AppChoiceOption("name", "Name"), AppChoiceOption("date", "Date")),
                    onSelect = { selected.value = it }, onDismissRequest = { visible.value = false },
                    toggle = AppDialogToggle("Reverse", reverse.value) { reverse.value = it },
                    actions = AppDialogActions("Confirm", "Cancel") { commits++; visible.value = false },
                )
            }
        }
        compose.onNodeWithText("Date").performClick()
        compose.onNodeWithText("Reverse").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Sort").assertDoesNotExist()
        assertEquals(0, commits)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Sort").assertDoesNotExist()
        assertEquals(1, commits)
    }

    @Test
    fun confirmDialogDownloadCommitsOnceAndCancelDoesNot() {
        val visible = mutableStateOf(true)
        var downloads = 0
        compose.setContent {
            AppTheme(false, false) {
                AppConfirmDialog(
                    visible = visible.value,
                    title = "发现新版本",
                    message = "1.0.1 可下载，是否更新？",
                    actions = AppDialogActions("下载", "取消") { downloads++; visible.value = false },
                    onDismissRequest = { visible.value = false },
                )
            }
        }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        assertEquals(0, downloads)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("下载").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        assertEquals(1, downloads)
    }

    @Test
    fun identityHeaderShowsNameSummaryVersionAndAction() {
        var clicks = 0
        compose.setContent {
            AppTheme(false, false) {
                AppIdentityHeader(
                    icon = R.drawable.ic_about,
                    name = "Music Tag",
                    summary = "本地音乐标签工具",
                    version = "1.0.0 (20)",
                    actionLabel = "检查更新",
                    onAction = { clicks++ },
                )
            }
        }
        compose.onNodeWithText("Music Tag").assertIsDisplayed()
        compose.onNodeWithText("本地音乐标签工具").assertIsDisplayed()
        compose.onNodeWithText("1.0.0 (20)").assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun searchTopBarExposesQueryAndCloseAction() {
        val query = mutableStateOf("")
        var closed = 0
        compose.setContent {
            AppTheme(false, false) {
                AppTopBar(
                    title = "Music Tag",
                    search = AppSearchState(query.value, "歌曲名、艺术家或专辑"),
                    onSearchQueryChange = { query.value = it },
                    onSearchClose = { closed++ },
                )
            }
        }
        compose.onNodeWithContentDescription("歌曲名、艺术家或专辑").assertIsDisplayed().performTextInput("七里香")
        compose.runOnIdle { assertEquals("七里香", query.value) }
        compose.onNodeWithContentDescription("Close search").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun primaryAndSecondarySelectionDoNotTriggerEachOther() {
        val primary = mutableStateOf(false)
        val secondary = mutableStateOf(false)
        var primaryCalls = 0
        var secondaryCalls = 0
        compose.setContent {
            AppTheme(false, false) {
                AppSelectionRow(
                    "Field", primary.value, { primaryCalls++; primary.value = it },
                    "Replace", "Replace field", secondary.value, { secondaryCalls++; secondary.value = it },
                )
            }
        }
        compose.onNodeWithText("Field").performClick()
        assertEquals(1, primaryCalls)
        assertEquals(0, secondaryCalls)
        compose.onNodeWithContentDescription("Replace field").performClick()
        assertEquals(1, primaryCalls)
        assertEquals(1, secondaryCalls)
    }

    @Test
    fun emptyContentRefreshCanBeDisabled() {
        val enabled = mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            AppTheme(false, false) {
                Box(Modifier.testTag("refresh")) {
                    AppRefreshableContentList(false, enabled.value, { refreshes++ }) {}
                }
            }
        }
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, refreshes)
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun refreshKeepsTheCallerListPosition() {
        val refreshing = mutableStateOf(false)
        lateinit var listState: LazyListState
        compose.setContent {
            listState = rememberLazyListState()
            AppTheme(false, false) {
                AppRefreshableContentList(refreshing.value, true, {}, listState = listState) {
                    items(40) { Text("Row $it", Modifier.height(64.dp)) }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        var before = 0 to 0
        compose.runOnIdle { before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset; refreshing.value = true }
        compose.waitForIdle()
        compose.runOnIdle { refreshing.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
    }

    @Test
    fun anchoredMenuDisablesActionsAndCanReverseDismissal() {
        val expanded = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            AppTheme(false, false) {
                AppScaffold(
                    modalVisible = expanded.value,
                    floatingAction = {
                        AppFloatingActionMenu(
                            "Actions", AppIcons.Edit, expanded.value,
                            listOf(AppMenuItem("disabled", "Unavailable", AppIcons.Edit, false), AppMenuItem("run", "Run", AppIcons.Check)),
                            { expanded.value = it }, { calls++; expanded.value = false }, collapseLabel = "Collapse",
                        )
                    },
                ) { AppContentList { item { Text("Page") } } }
            }
        }
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Unavailable").assertIsNotEnabled().performClick()
        assertEquals(0, calls)
        compose.onNodeWithContentDescription("Collapse").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded.value = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Run").assertExists()
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Run").assertIsDisplayed().performClick()
        compose.onNodeWithText("Run").assertDoesNotExist()
        assertEquals(1, calls)
    }

    @Test
    fun choiceGridAndInputRemainOperableAtDoubleFontScale() {
        val selected = mutableStateOf("a")
        val text = mutableStateOf("")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme(false, false) {
                    AppContentList {
                        item {
                            AppChoiceGrid(listOf(AppChoiceOption("a", "标题 - 艺术家"), AppChoiceOption("b", "艺术家 - 标题")), selected.value, true) { selected.value = it }
                        }
                        item { AppTextField(text.value, "自定义模板", true) { text.value = it } }
                    }
                }
            }
        }
        compose.onNodeWithText("艺术家 - 标题").performClick()
        assertEquals("b", selected.value)
        compose.onNodeWithText("自定义模板").performScrollTo().performTextInput("@2 - @1")
        assertEquals("@2 - @1", text.value)
    }
}
