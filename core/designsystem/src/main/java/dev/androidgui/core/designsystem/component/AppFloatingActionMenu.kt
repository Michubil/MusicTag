package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import dev.androidgui.core.designsystem.R
import dev.androidgui.core.designsystem.icon.AppIcon
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.tokens.*
import kotlin.math.roundToInt

/** The modal menu is anchored to the measured FAB, including the scaffold's actual insets. */
@Composable
fun AppFloatingActionMenu(
    label: String,
    icon: AppIcon,
    expanded: Boolean,
    actions: List<AppMenuItem>,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (String) -> Unit,
    collapseLabel: String = stringResource(R.string.app_collapse_menu),
) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
        AppFloatingAction(label, icon) { onExpandedChange(true) }
    }
    AppModalWindow(expanded, { onExpandedChange(false) }, TransformOrigin(1f, 1f)) { animatedModifier ->
        val density = LocalDensity.current
        val safeTop = WindowInsets.safeDrawing.getTop(density)
        val safeLeft = WindowInsets.safeDrawing.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    animatedModifier.widthIn(max = AppDimensions.DialogMaxWidth)
                        .semantics { paneTitle = label },
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large),
                ) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                    ) {
                        actions.forEach { action ->
                            Row(
                                Modifier.heightIn(min = AppDimensions.MinimumTouchTarget)
                                    .clickable(enabled = action.enabled && expanded, role = Role.Button) { onAction(action.id) }
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                            ) {
                                Surface(
                                    Modifier.weight(1f, fill = false),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(action.label, Modifier.padding(AppSpacing.Medium),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    Modifier.size(AppDimensions.MinimumTouchTarget),
                                    shape = MaterialTheme.shapes.large,
                                    color = if (action.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (action.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Box(contentAlignment = Alignment.Center) { AppIconView(action.icon, null, Modifier.size(AppIconSize.Standard)) }
                                }
                            }
                        }
                    }
                    AppFloatingAction(collapseLabel, AppIcons.Close) { onExpandedChange(false) }
                }
            },
        ) { measurables, constraints ->
            val right = anchor.right.roundToInt().coerceIn(safeLeft, constraints.maxWidth)
            val bottom = anchor.bottom.roundToInt().coerceIn(safeTop, constraints.maxHeight)
            val menu = measurables.single().measure(Constraints(maxWidth = right - safeLeft, maxHeight = bottom - safeTop))
            layout(constraints.maxWidth, constraints.maxHeight) { menu.place(right - menu.width, bottom - menu.height) }
        }
    }
}
