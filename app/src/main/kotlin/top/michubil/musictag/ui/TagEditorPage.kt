package top.michubil.musictag.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import dev.androidgui.core.designsystem.component.*
import top.michubil.musictag.data.model.MetadataField

@Composable
fun TagEditorPage(state: TagEditorState, busy: Boolean, onAction: (MainAction) -> Unit) {
    val draft = state.draft
    val enabled = !busy && !state.loading && !state.coverLoading
    AppContentList {
        item(key = "cover") {
            val replacing = MetadataField.COVER in draft.changed
            AppArtworkEditor(
                artwork = state.artwork?.asImageBitmap(),
                showArtwork = !state.batch,
                title = if (state.batch) "批量编辑 · ${state.count} 个文件" else
                    draft.text[MetadataField.TITLE]?.takeIf(String::isNotBlank) ?: state.fileName ?: "编辑标签",
                subtitle = when {
                    state.loading -> if (state.batch) "正在读取所选文件的标签" else "正在读取歌曲封面与标签"
                    replacing && draft.cover == null -> "保存时移除封面"
                    replacing -> "已选择新封面 · 保存后应用于 ${state.count} 个文件"
                    state.batch -> "未修改时保留各文件的原封面"
                    else -> state.fileName ?: "选择文件后可编辑封面"
                },
                placeholder = when {
                    state.loading -> "正在读取封面"
                    replacing && draft.cover == null -> "已选择移除封面"
                    draft.hasExistingCover -> "暂无封面预览"
                    else -> "暂无封面"
                },
                selected = replacing, enabled = enabled && state.count > 0,
                onSelectedChange = { onAction(MainAction.SetTagSelected(MetadataField.COVER, it)) },
                onChoose = { onAction(MainAction.ChooseTagCover) }, onRemove = { onAction(MainAction.RemoveTagCover) },
            )
        }
        item { AppSupportingText("仅保存勾选字段；留空即清除。") }
        if (state.coverLoading) item { AppProgress(message = "正在读取封面") }
        if (state.loading) item { AppProgress(message = "正在读取现有标签") }
        state.error?.let { item { ErrorState(title = "无法读取标签", message = it) } }
        if (state.failures.isNotEmpty()) item {
            ErrorState(title = "跳过 ${state.failures.size} 个文件", message = state.failures.joinToString("\n"))
        }
        if (!state.loading && state.count == 0 && state.error == null) {
            item { EmptyState(title = "没有可编辑的 FLAC、MP3 或 WAV 文件") }
        }
        if (state.count > 0) {
            MetadataField.textFields.forEach { field ->
                item(key = field.name) {
                    val hint = buildList {
                        if (field in draft.mixed && field !in draft.changed) add("多个不同值，未勾选时分别保留")
                        when (field) {
                            MetadataField.ARTISTS -> add("每行一个艺术家")
                            MetadataField.DATE -> add("YYYY、YYYY-MM 或 YYYY-MM-DD")
                            MetadataField.TRACK, MetadataField.DISC -> add("编号或 编号/总数")
                            else -> Unit
                        }
                    }.joinToString("；").ifEmpty { null }
                    AppEditableField(
                        label = field.label, value = draft.text[field].orEmpty(), selected = field in draft.changed,
                        enabled = enabled, hint = hint, kind = when (field) {
                            MetadataField.ARTISTS -> AppTextInputKind.ShortMultiline
                            MetadataField.LYRICS -> AppTextInputKind.LongMultiline
                            else -> AppTextInputKind.SingleLine
                        },
                        onSelectedChange = { onAction(MainAction.SetTagSelected(field, it)) },
                        onValueChange = { onAction(MainAction.SetTagText(field, it)) },
                    )
                }
            }
            state.validation?.let { item { ErrorState(title = "请检查输入", message = it) } }
        }
    }
}

@Composable
fun TagEditorActions(state: TagEditorState, canEditSelection: Boolean, onAction: (MainAction) -> Unit) {
    AppActionBar(
        primaryLabel = "保存 ${state.count} 个文件", secondaryLabel = "重新读取",
        enabled = canEditSelection && state.canSave, secondaryEnabled = canEditSelection && !state.loading && !state.coverLoading,
        onPrimary = { onAction(MainAction.SaveTags) }, onSecondary = { onAction(MainAction.ReloadTags) },
    )
}
