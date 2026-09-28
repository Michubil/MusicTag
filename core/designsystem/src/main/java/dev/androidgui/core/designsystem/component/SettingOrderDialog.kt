package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppSpacing

data class AppOrderItem(val id: String, val label: String, val enabled: Boolean)

@Composable
fun AppOrderDraftDialog(
    visible: Boolean,
    title: String,
    items: List<AppOrderItem>,
    enabled: Boolean,
    confirmLabel: String,
    dismissLabel: String,
    moveUpLabel: String,
    moveDownLabel: String,
    dragLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: (List<AppOrderItem>) -> Unit,
) {
    var draft by remember(visible, items) { mutableStateOf(items) }
    AppModalSurface(visible, title, onDismissRequest) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(AppSpacing.ExtraLarge),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.Large)) {
            draft.forEachIndexed { index, item ->
                key(item.id) {
                    OrderRow(
                        item = item,
                        enabled = enabled,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.lastIndex,
                        moveUpLabel = moveUpLabel,
                        moveDownLabel = moveDownLabel,
                        dragLabel = dragLabel,
                        onCheckedChange = { checked -> draft = draft.replace(index, item.copy(enabled = checked)) },
                        onMoveUp = { draft = draft.move(index, index - 1) },
                        onMoveDown = { draft = draft.move(index, index + 1) },
                        onDragBy = { delta ->
                            val target = (index + delta).coerceIn(0, draft.lastIndex)
                            if (target != index) draft = draft.move(index, target)
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(AppSpacing.Large),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismissRequest, enabled = visible) { Text(dismissLabel) }
            TextButton(onClick = { onConfirm(draft) }, enabled = enabled && visible) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun OrderRow(
    item: AppOrderItem,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveUpLabel: String,
    moveDownLabel: String,
    dragLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragBy: (Int) -> Unit,
) {
    val density = LocalDensity.current
    var dragOffset by remember(item.id) { mutableFloatStateOf(0f) }
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    Row(Modifier.fillMaxWidth().heightIn(min = AppDimensions.MinimumTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = AppDimensions.MinimumTouchTarget).semantics(mergeDescendants = true) {}
                .toggleable(item.enabled, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, Modifier.weight(1f).padding(start = AppSpacing.Small), style = MaterialTheme.typography.bodyLarge)
            AppSwitchControl(item.enabled, enabled, remember { androidx.compose.foundation.interaction.MutableInteractionSource() })
        }
        Box(
            modifier = Modifier.size(AppDimensions.MinimumTouchTarget).semantics {
                contentDescription = "${item.label}，$dragLabel"
                customActions = if (enabled) buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                } else emptyList()
            }.pointerInput(item.id, enabled, density) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragCancel = { dragOffset = 0f },
                    onDragEnd = { dragOffset = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        val row = with(density) { AppDimensions.MinimumTouchTarget.toPx() }
                        val shift = (dragOffset / row).toInt()
                        if (shift != 0) {
                            currentOnDragBy(shift)
                            dragOffset -= shift * row
                        }
                    },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(icon = AppIcons.Menu, contentDescription = null)
        }
    }
}

private fun List<AppOrderItem>.move(from: Int, to: Int): List<AppOrderItem> {
    if (from !in indices || to !in indices || from == to) return this
    val next = toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

private fun List<AppOrderItem>.replace(index: Int, item: AppOrderItem): List<AppOrderItem> =
    toMutableList().also { it[index] = item }
