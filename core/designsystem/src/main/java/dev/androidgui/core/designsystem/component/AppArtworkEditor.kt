package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppSpacing

/** Cover, identity and local actions share one surface; only saving belongs in the bottom bar. */
@Composable
fun AppArtworkEditor(
    artwork: ImageBitmap?, title: String, subtitle: String, placeholder: String,
    selected: Boolean, enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit, onChoose: () -> Unit, onRemove: () -> Unit,
    showArtwork: Boolean = true,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            if (showArtwork) Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(artwork, "歌曲封面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                        AppIconView(AppIcons.Music, null, Modifier.size(AppDimensions.StateIconSize))
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).heightIn(min = AppDimensions.MinimumTouchTarget)
                            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelectedChange),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(selected, onCheckedChange = null, enabled = enabled)
                        Text("修改封面", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onChoose, enabled = enabled) { Text("选择封面") }
                    TextButton(onClick = onRemove, enabled = enabled) { Text("移除封面") }
                }
            }
        }
    }
}
