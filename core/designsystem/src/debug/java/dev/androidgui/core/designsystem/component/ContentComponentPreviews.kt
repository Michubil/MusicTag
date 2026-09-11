package dev.androidgui.core.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.theme.AppTheme

@Preview(name = "360dp content", widthDp = 360, heightDp = 800, showSystemUi = true)
@Preview(name = "393dp large text", widthDp = 393, heightDp = 800, fontScale = 1.5f)
@Preview(name = "412dp double text", widthDp = 412, heightDp = 800, fontScale = 2f, locale = "zh-rCN")
@Preview(name = "360dp dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ContentComponentPreviews() {
    var selected by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf("a") }
    var input by remember { mutableStateOf("") }
    AppTheme(isSystemInDarkTheme(), false) {
        AppScaffold(
            topBar = { AppTopBar("Content / 内容") },
            bottomActions = { AppActionBar("确认 Confirm", "重新读取 Reload", {}, {}, true) },
            bottomBar = { AppNavigationBar(listOf(AppNavigationDestination("文件", AppIcons.Empty)), 0, {}) },
        ) {
            AppContentList {
                item {
                    AppThreeLineContentRow(
                        "长文件名 Long title that should ellipsize", "艺术家 / Supporting text", "专辑 / Detail",
                        AppIcons.Empty, null, selected, "选择内容", { selected = !selected }, { selected = it },
                    )
                }
                item { AppSelectionRow("字段", selected, { selected = it }, "覆盖", "覆盖字段", true, {}) }
                item { AppChoiceGrid(listOf(AppChoiceOption("a", "标题 - 艺术家"), AppChoiceOption("b", "艺术家 - 标题")), choice, true) { choice = it } }
                item { AppTextField(input, "自定义模板", true) { input = it } }
            }
        }
    }
}
