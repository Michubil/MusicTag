package dev.androidgui.core.designsystem.component

// Music Tag adaptation: optional toggle and commit/cancel actions for draft-based sorting.

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.androidgui.core.designsystem.theme.AppSystemBars
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppElevation
import dev.androidgui.core.designsystem.tokens.AppMotion
import dev.androidgui.core.designsystem.tokens.AppSpacing

@Immutable
data class AppChoiceOption<T>(val value: T, val label: String)

data class AppDialogToggle(val label: String, val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)
data class AppDialogActions(val confirmLabel: String, val dismissLabel: String, val onConfirm: () -> Unit)

/** Keep this composable mounted and drive [visible] to preserve the exit animation. */
@Composable
fun <T> AppSingleChoiceDialog(
    visible: Boolean,
    title: String,
    options: List<AppChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
    toggle: AppDialogToggle? = null,
    actions: AppDialogActions? = null,
) {
    AppModalSurface(visible, title, onDismissRequest) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(AppSpacing.ExtraLarge),
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(bottom = AppSpacing.Large),
        ) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimensions.MinimumTouchTarget)
                        .selectable(
                            selected = selectedValue == option.value,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (visible) onSelect(option.value) },
                        )
                        .padding(horizontal = AppSpacing.ExtraLarge, vertical = AppSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraLarge),
                ) {
                    RadioButton(
                        selected = selectedValue == option.value,
                        onClick = null,
                        enabled = enabled,
                        modifier = Modifier.size(AppDimensions.ChoiceIndicatorSize).clearAndSetSemantics {},
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledSelectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            disabledUnselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (toggle != null) {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppDimensions.MinimumTouchTarget)
                        .toggleable(
                            value = toggle.checked, enabled = enabled && visible, role = Role.Switch,
                            onValueChange = toggle.onCheckedChange,
                        )
                        .padding(horizontal = AppSpacing.ExtraLarge, vertical = AppSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraLarge),
                ) {
                    Text(toggle.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    AppSwitchControl(toggle.checked, enabled, interaction)
                }
            }
        }
        if (actions != null) {
            Row(
                Modifier.fillMaxWidth().padding(AppSpacing.Large),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest, enabled = visible) { Text(actions.dismissLabel) }
                TextButton(onClick = actions.onConfirm, enabled = enabled && visible) { Text(actions.confirmLabel) }
            }
        }
    }
}

@Composable
internal fun AppModalSurface(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    AppModalWindow(visible, onDismissRequest) { animatedModifier ->
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(AppSpacing.ExtraLarge),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = animatedModifier.widthIn(max = AppDimensions.DialogMaxWidth).fillMaxWidth()
                    .semantics { paneTitle = title },
                shape = RoundedCornerShape(AppDimensions.DialogCornerRadius),
                color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = AppElevation.Level0,
                shadowElevation = AppElevation.Dialog,
            ) {
                Column(content = content)
            }
        }
    }
}

/** Shared window, scrim, system bars and frozen animation for centered and anchored modals. */
@Composable
internal fun AppModalWindow(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    content: @Composable (Modifier) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    val transition = rememberTransition(visibility, label = "appDialog")
    val alpha = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(AppMotion.DialogEnterMillis, easing = AppMotion.DialogEnterEasing)
            } else {
                tween(AppMotion.DialogExitMillis, easing = AppMotion.DialogExitEasing)
            }
        },
        label = "dialogOpacity",
    ) { if (it) 1f else 0f }
    val scale = transition.animateFloat(
        transitionSpec = {
            if (targetState) tween(AppMotion.DialogEnterMillis, easing = AppMotion.DialogEnterEasing) else snap()
        },
        label = "dialogScale",
    ) { if (it) 1f else AppMotion.DialogStartScale }

    if (transition.currentState || transition.targetState) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnClickOutside = false,
            ),
        ) {
            AppSystemBars()
            val view = LocalView.current
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    // Compose owns the frozen M3 animation; prevent a second platform animation.
                    window.setWindowAnimations(0)
                    window.setDimAmount(0f)
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = AppMotion.DialogScrimAlpha * alpha.value))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest,
                        )
                        .clearAndSetSemantics {},
                )
                content(Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    this.transformOrigin = transformOrigin
                    // M3's exit only fades; keep the fully opened scale while leaving.
                    scaleX = if (visible) scale.value else 1f
                    scaleY = if (visible) scale.value else 1f
                })
            }
        }
    }
}
