package dev.androidgui.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppSpacing

internal val LocalAppContentBottomPadding = staticCompositionLocalOf { AppSpacing.None }

/** Pull-to-refresh shell for list or grid content; loading status replaces the indicator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRefreshableContent(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    status: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        Modifier.fillMaxSize().pullToRefresh(
            isRefreshing = refreshing,
            state = state,
            enabled = enabled,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        if (status != null) Box(Modifier.align(Alignment.TopCenter)) { status() }
        else PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Keeps short/empty lists refreshable without moving the viewport or changing list identity. */
@Composable
fun AppRefreshableContentList(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    compact: Boolean = false,
    status: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    AppRefreshableContent(refreshing, enabled, onRefresh, status) {
        AppContentList(state = listState, compact = compact, content = content)
    }
}

/**
 * Standard page list. Bottom chrome is overlaid, so its stable safe area belongs in scrollable
 * content padding, not in the viewport's height. The last row can always clear the visible bar.
 */
@Composable
fun AppContentList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    compact: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val bottomPadding = LocalAppContentBottomPadding.current
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .consumeWindowInsets(PaddingValues(bottom = bottomPadding)),
        contentPadding = PaddingValues(
            start = AppDimensions.ScreenHorizontalPadding,
            top = AppDimensions.ContentVerticalPadding,
            end = AppDimensions.ScreenHorizontalPadding,
            bottom = AppDimensions.ContentVerticalPadding + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(if (compact) AppSpacing.Small else AppSpacing.Large),
        content = content,
    )
}
