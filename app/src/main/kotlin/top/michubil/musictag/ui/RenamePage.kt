package top.michubil.musictag.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import dev.androidgui.core.designsystem.component.*
import dev.androidgui.core.designsystem.icon.AppIcons
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.rename.RenameTag

@Composable
fun RenamePage(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppContentList {
        item { AppSupportingText("已选择 ${state.selected.size} 项 · 使用文件现有标签，保留扩展名") }
        item {
            AppExpandableSection(title = "命名规则", summary = "${state.renamePreset.label} · ${state.renamePattern}") {
                AppChoiceGrid(
                    options = RenamePreset.entries.map { AppChoiceOption(it, it.pattern.ifEmpty { it.label }) },
                    selectedValue = state.renamePreset, enabled = !state.busy,
                    onSelect = { onAction(MainAction.SetRenamePreset(it)) },
                )
                if (state.renamePreset == RenamePreset.CUSTOM) {
                    AppTextField(
                        value = state.renameCustomPattern, label = "自定义模板，例如 @1-@2",
                        enabled = !state.busy, onValueChange = { onAction(MainAction.SetRenamePattern(it)) },
                    )
                }
                AppExpandableSection(title = "参数说明") {
                    AppSupportingText(RenameTag.entries.chunked(2).joinToString("\n") { pair ->
                        pair.joinToString("    ") { "@${it.token} · ${it.label}" }
                    })
                    AppSupportingText("音轨号至少两位；年份取四位。多个艺术家用 & 连接。@@ 表示 @；无需填写扩展名。\n不允许的文件名字符会替换为 _，缺少标签和同目录重名会跳过。")
                }
            }
        }
        when {
            state.renameLoading -> item { AppProgress(message = state.renameLoadingMessage) }
            state.renameError != null -> item { ErrorState(title = "无法生成预览", message = state.renameError) }
            state.renameEntries.isEmpty() -> item { EmptyState(title = "没有可重命名的 FLAC、MP3 或 WAV 文件") }
            else -> {
                item {
                    AppSupportingText("预览 · 待修改 ${state.renameEntries.count { it.willRename }} 个 · 跳过 ${state.renameEntries.count { it.error != null }} 个 · 无需修改 ${state.renameEntries.count { it.error == null && !it.willRename }} 个")
                }
                items(state.renameEntries, key = { it.document.uri }) { entry ->
                    AppContentRow(
                        title = entry.document.name,
                        summary = if (entry.error != null) "跳过：${entry.error}" else if (entry.willRename) "→ ${entry.newName}" else "文件名未变化",
                        icon = if (entry.error != null) AppIcons.Error else AppIcons.Music,
                        onClick = {}, enabled = false,
                    )
                }
            }
        }
    }
}

@Composable
fun RenameActions(state: MainUiState, onAction: (MainAction) -> Unit) {
    AppActionBar(
        primaryLabel = "重命名 ${state.renameEntries.count { it.willRename }} 个文件",
        secondaryLabel = "重新读取",
        enabled = state.canRename, secondaryEnabled = state.canEditSelection && !state.renameLoading,
        onPrimary = { onAction(MainAction.StartRenaming) }, onSecondary = { onAction(MainAction.ReloadRename) },
    )
}
