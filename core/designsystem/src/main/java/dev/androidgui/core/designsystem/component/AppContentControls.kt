package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.androidgui.core.designsystem.icon.AppIcon
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppElevation
import dev.androidgui.core.designsystem.tokens.AppIconSize
import dev.androidgui.core.designsystem.tokens.AppSpacing

/** Content and selection semantics only; cover geometry and text hierarchy belong here. */
@Composable
fun AppContentRow(
    title: String,
    summary: String,
    icon: AppIcon,
    onClick: () -> Unit,
    details: String? = null,
    artwork: ImageBitmap? = null,
    selected: Boolean? = null,
    selectionLabel: String = title,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    selectionEnabled: Boolean = enabled,
) {
    ContentRow(icon, onClick, artwork, selected, selectionLabel, onSelectedChange, enabled, selectionEnabled) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (details != null) {
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Reserve all three lines before metadata arrives; content length never changes row geometry. */
@Composable
fun AppThreeLineContentRow(
    title: String,
    supporting: String,
    detail: String,
    icon: AppIcon,
    artwork: ImageBitmap?,
    selected: Boolean,
    selectionLabel: String,
    onClick: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ContentRow(icon, onClick, artwork, selected, selectionLabel, onSelectedChange, enabled) {
        ContentLine(title, MaterialTheme.typography.titleMedium)
        ContentLine(supporting, MaterialTheme.typography.bodyMedium, supporting = true)
        ContentLine(detail, MaterialTheme.typography.bodyMedium, supporting = true)
    }
}

@Composable
private fun ContentLine(text: String, style: TextStyle, supporting: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        color = if (supporting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        minLines = 1,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ContentRow(
    icon: AppIcon,
    onClick: () -> Unit,
    artwork: ImageBitmap?,
    selected: Boolean?,
    selectionLabel: String,
    onSelectedChange: ((Boolean) -> Unit)?,
    enabled: Boolean,
    selectionEnabled: Boolean = enabled,
    content: @Composable () -> Unit,
) {
    Surface(
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppDimensions.PreferenceMinHeight)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            Box(
                Modifier.size(AppDimensions.ArtworkSize).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    AppIconView(icon, null, Modifier.size(AppIconSize.Standard))
                }
            }
            Column(Modifier.weight(1f)) {
                content()
            }
            if (selected != null && onSelectedChange != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                    enabled = enabled && selectionEnabled,
                    modifier = Modifier.semantics { contentDescription = selectionLabel },
                )
            }
        }
    }
}

/** A primary selection and an independent secondary switch, each with its own accessible action. */
@Composable
fun AppSelectionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    secondaryLabel: String,
    secondaryDescription: String,
    secondaryChecked: Boolean,
    onSecondaryChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    secondaryEnabled: Boolean = true,
) {
    val selectionInteraction = remember { MutableInteractionSource() }
    PreferenceSurface(interactionSource = selectionInteraction) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Small, vertical = AppSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = AppDimensions.MinimumTouchTarget)
                    .toggleable(
                        value = checked, enabled = enabled, role = Role.Checkbox,
                        interactionSource = selectionInteraction, indication = ripple(),
                        onValueChange = onCheckedChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            Row(
                Modifier.heightIn(min = AppDimensions.MinimumTouchTarget)
                    .semantics { contentDescription = secondaryDescription }
                    .toggleable(
                        value = secondaryChecked, enabled = enabled && secondaryEnabled, role = Role.Switch,
                        interactionSource = selectionInteraction, indication = ripple(),
                        onValueChange = onSecondaryChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
                AppSwitchControl(secondaryChecked, enabled && secondaryEnabled, selectionInteraction)
            }
        }
    }
}

@Composable
fun AppFloatingAction(label: String, icon: AppIcon, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(AppDimensions.FloatingActionSize).semantics { contentDescription = label },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = AppElevation.Level2,
            pressedElevation = AppElevation.Level2,
            focusedElevation = AppElevation.Level2,
            hoveredElevation = AppElevation.Dialog,
        ),
    ) {
        AppIconView(icon, null, Modifier.size(AppIconSize.Standard))
    }
}

/** Lives in the scaffold's bottom chrome; AppScaffold owns the single system bottom inset. */
@Composable
fun AppActionBar(
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    enabled: Boolean,
    secondaryEnabled: Boolean = enabled,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSecondary,
                enabled = secondaryEnabled,
                modifier = Modifier.weight(1f).heightIn(min = AppDimensions.MinimumTouchTarget),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
            AppButton(primaryLabel, onPrimary, modifier = Modifier.weight(1f), enabled = enabled)
        }
    }
}
