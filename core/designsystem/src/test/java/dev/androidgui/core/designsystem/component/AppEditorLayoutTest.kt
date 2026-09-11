package dev.androidgui.core.designsystem.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.androidgui.core.designsystem.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Measured device width and the content height remaining above its pinned action/navigation bars. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w361dp-h794dp")
class AppEditorLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun coverIsExpandedWithoutCollapseControlsAndFieldsRemainReachable() {
        compose.setContent {
            AppTheme(false, false) {
                Box(Modifier.fillMaxWidth().height(546.dp)) {
                    AppContentList {
                        item { Cover() }
                        item { AppSupportingText("仅保存勾选字段；留空即清除。") }
                        item { AppEditableField("标题", "歌曲标题", false, true, null, AppTextInputKind.SingleLine, {}, {}) }
                    }
                }
            }
        }
        compose.onNodeWithText("暂无封面").assertIsDisplayed()
        compose.onNodeWithText("展开封面").assertDoesNotExist()
        compose.onNodeWithText("收起封面").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("歌曲标题").assertIsDisplayed()
    }

    @Test
    fun directoryCanOpenWhileItsSelectionIsDisabled() {
        var opens = 0
        var selections = 0
        compose.setContent {
            AppTheme(false, false) {
                AppContentRow("Folder", "文件夹", dev.androidgui.core.designsystem.icon.AppIcons.Folder,
                    onClick = { opens++ }, selected = false, selectionLabel = "选择 Folder",
                    onSelectedChange = { selections++ }, enabled = true, selectionEnabled = false)
            }
        }
        compose.onNodeWithText("Folder").performClick()
        compose.onNodeWithContentDescription("选择 Folder").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, selections) }
    }

    @Test
    fun shortMultivalueFieldGrowsWithContentWhileLyricsReserveSpace() {
        val artists = mutableStateOf("Artist")
        compose.setContent {
            AppTheme(false, false) {
                Column {
                    AppEditableField("艺术家", artists.value, false, true, null, AppTextInputKind.ShortMultiline, {}, { artists.value = it })
                    AppEditableField("歌词", "Verse", false, true, null, AppTextInputKind.LongMultiline, {}, {})
                }
            }
        }
        val before = compose.onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot.height
        val lyrics = compose.onNodeWithText("Verse").fetchSemanticsNode().boundsInRoot.height
        assertTrue(before < lyrics)
        compose.runOnIdle { artists.value = "Artist\nSecond\nThird" }
        assertTrue(compose.onNodeWithText("Artist\nSecond\nThird").fetchSemanticsNode().boundsInRoot.height > before)
    }

    @Test
    fun collapsedRulesExposePreviewBeforeLongHelpAndRetainTheirSummary() {
        compose.setContent {
            AppTheme(false, false) {
                AppContentList {
                    item {
                        AppExpandableSection("命名规则", "@2 - @1") {
                            AppSupportingText("模板参数说明")
                        }
                    }
                    item { AppSupportingText("old.mp3 → Artist - Title.mp3") }
                }
            }
        }
        compose.onNodeWithText("old.mp3 → Artist - Title.mp3").assertIsDisplayed()
        compose.onNodeWithText("模板参数说明").assertDoesNotExist()
        compose.onNodeWithText("命名规则").performClick()
        compose.onNodeWithText("模板参数说明").assertIsDisplayed()
        compose.onNodeWithText("命名规则").performClick()
        compose.onNodeWithText("模板参数说明").assertDoesNotExist()
    }

    @Test
    fun loadingOverlayDoesNotMoveExistingRows() {
        val loading = mutableStateOf(false)
        compose.setContent {
            AppTheme(false, false) {
                AppRefreshableContentList(false, !loading.value, {}, status = if (loading.value) {
                    { AppLoadingStatus("正在筛选音频 16 / 497", 16f / 497) }
                } else null) {
                    items(10) { AppSupportingText("文件 $it") }
                }
            }
        }
        val before = compose.onNodeWithText("文件 5").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("正在筛选音频 16 / 497").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("文件 5").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { loading.value = false }
        assertEquals(before, compose.onNodeWithText("文件 5").fetchSemanticsNode().boundsInRoot)
    }

    @androidx.compose.runtime.Composable
    private fun Cover() = AppArtworkEditor(null, "封面标题", "song.flac", "暂无封面", false, true, {}, {}, {})
}
