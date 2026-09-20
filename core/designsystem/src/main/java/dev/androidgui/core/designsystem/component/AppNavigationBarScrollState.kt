package dev.androidgui.core.designsystem.component

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Compose equivalent of LibChecker's consumed-scroll direction policy. */
@Stable
internal class AppNavigationBarScrollState(
    private val touchExplorationEnabled: Boolean = false,
    private val pinned: Boolean = false,
) : NestedScrollConnection {
    var hidden: Boolean by mutableStateOf(false)
        private set

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // Compose deltas have the opposite sign from RecyclerView's dyConsumed.
        // Only actual vertical content movement matters, not overscroll or horizontal gestures.
        if (consumed.y < 0f && !touchExplorationEnabled && !pinned) {
            hidden = true
        } else if (consumed.y > 0f) {
            hidden = false
        }
        // Observe scrolling without stealing any distance from the content.
        return Offset.Zero
    }
}
