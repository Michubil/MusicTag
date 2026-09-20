package dev.androidgui.core.designsystem.component

// Music Tag adaptation: share the existing switch control with selection and sort components.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import dev.androidgui.core.designsystem.icon.AppIcon
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppIconSize
import dev.androidgui.core.designsystem.tokens.AppSpacing
import dev.androidgui.core.designsystem.theme.AppSwitchMotionScheme

@Composable
fun SettingSwitchRow(
    title: String,
    summary: String? = null,
    icon: AppIcon,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    PreferenceSurface(interactionSource = interactionSource) {
        PreferenceHeader(
            title = title,
            summary = summary,
            icon = icon,
            enabled = enabled,
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = ripple(color = MaterialTheme.colorScheme.onSurface),
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
            trailing = {
                AppSwitchControl(checked, enabled, interactionSource)
            },
        )
    }
}

@Composable
fun SettingChoiceRow(
    title: String,
    value: String,
    icon: AppIcon,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    PreferenceSurface(interactionSource = interactionSource) {
        PreferenceHeader(
            title = title,
            summary = value,
            icon = icon,
            enabled = enabled,
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = MaterialTheme.colorScheme.onSurface),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
    }
}

@Composable
fun SettingInfoRow(
    title: String,
    summary: String? = null,
    icon: AppIcon,
) {
    PreferenceSurface(interactionSource = null) {
        PreferenceHeader(
            title = title,
            summary = summary,
            icon = icon,
            enabled = true,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
    }
}

@Composable
private fun PreferenceHeader(
    title: String,
    summary: String?,
    icon: AppIcon,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppDimensions.PreferenceMinHeight)
            .padding(
                start = AppDimensions.HeaderStartPadding,
                top = AppDimensions.HeaderTopPadding,
                end = AppDimensions.HeaderEndPadding,
                bottom = AppDimensions.HeaderBottomPadding,
            )
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppDimensions.IconContainer)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Setting),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(AppDimensions.IconToTextGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            if (summary != null) {
                Spacer(modifier = Modifier.heightIn(min = AppDimensions.TitleToSummaryGap))
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(AppSpacing.Medium))
            trailing()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppSwitchControl(checked: Boolean, enabled: Boolean, interactionSource: MutableInteractionSource) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        motionScheme = AppSwitchMotionScheme,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .width(AppDimensions.SwitchWidth)
                .heightIn(min = AppDimensions.SwitchHeight)
                .clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                checkedIconColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledCheckedThumbColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
                disabledCheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledCheckedBorderColor = Color.Transparent,
                disabledCheckedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledUncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledUncheckedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        )
    }
}
