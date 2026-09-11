package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.androidgui.core.designsystem.tokens.AppSpacing
import kotlinx.coroutines.delay

private const val LoadingStatusDelayMillis = 300L

/** Overlaid by refreshable lists, so progress updates never change row positions or scroll offset. */
@Composable
fun AppLoadingStatus(message: String, progress: Float? = null, deferDisplay: Boolean = false) {
    // Brief reads finish without flashing a status card; disposal cancels the pending reveal.
    var visible by remember(deferDisplay) { mutableStateOf(!deferDisplay) }
    LaunchedEffect(deferDisplay) {
        if (deferDisplay) {
            delay(LoadingStatusDelayMillis)
            visible = true
        }
    }
    if (!visible) return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Large),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
