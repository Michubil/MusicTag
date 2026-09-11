package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidgui.core.designsystem.icon.AppIcon
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppElevation
import dev.androidgui.core.designsystem.tokens.AppSpacing

/** Standard text inside an expanded setting or informational surface. */
@Composable
fun AppSupportingText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = AppDimensions.MinimumTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = AppElevation.Level0,
            pressedElevation = AppElevation.Level0,
            focusedElevation = AppElevation.Level0,
            hoveredElevation = AppElevation.Level1,
            disabledElevation = AppElevation.Level0,
        ),
        contentPadding = PaddingValues(horizontal = AppSpacing.ExtraLarge, vertical = AppSpacing.Small),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Stable
class AppSnackbarState internal constructor(
    internal val hostState: SnackbarHostState,
) {
    suspend fun showMessage(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = actionLabel == null,
    ): Boolean = hostState.showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    ) == SnackbarResult.ActionPerformed
}

@Composable
fun rememberAppSnackbarState(): AppSnackbarState = remember {
    AppSnackbarState(SnackbarHostState())
}

@Composable
internal fun AppSnackbarHost(state: AppSnackbarState) {
    SnackbarHost(hostState = state.hostState) { data ->
        Snackbar(
            data,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            actionContentColor = MaterialTheme.colorScheme.inversePrimary,
            dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
fun AppProgress(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.ExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(AppDimensions.StateIconSize),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 4.dp,
        )
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AppStateContent(
        icon = AppIcons.Empty,
        title = title,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AppStateContent(
        icon = AppIcons.Error,
        title = title,
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
private fun AppStateContent(
    icon: AppIcon,
    title: String,
    message: String?,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.ExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        AppIconView(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(AppDimensions.StateIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (actionLabel != null && onAction != null) {
            AppButton(text = actionLabel, onClick = onAction)
        }
    }
}
