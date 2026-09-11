package top.michubil.musictag.ui

import androidx.compose.runtime.Composable
import dev.androidgui.core.designsystem.component.AppFloatingActionMenu
import dev.androidgui.core.designsystem.component.AppMenuItem
import dev.androidgui.core.designsystem.icon.AppIcons

@Composable
fun FileActions(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppFloatingActionMenu(
        label = "编辑文件",
        icon = AppIcons.Edit,
        collapseLabel = "收起编辑菜单",
        expanded = state.editMenuExpanded,
        actions = listOf(
            AppMenuItem("tags", "编辑标签", AppIcons.Tag, state.canEditSelection),
            AppMenuItem("rename", "文件名修改", AppIcons.Edit, state.canEditSelection),
            AppMenuItem("match", "自动匹配标签", AppIcons.AutoTag, state.canEditSelection),
        ),
        onExpandedChange = { onAction(MainAction.SetEditMenu(it)) },
        onAction = { id ->
            when (id) {
                "tags" -> onAction(MainAction.ShowTagEditor)
                "rename" -> onAction(MainAction.ShowRename)
                "match" -> onAction(MainAction.ShowOptions)
            }
        },
    )
}
