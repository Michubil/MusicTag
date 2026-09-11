package dev.androidgui.core.designsystem.component

// Music Tag adaptation: floating-action slot; existing insets, scroll behavior and modal blur retained.

import android.view.accessibility.AccessibilityManager
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.androidgui.core.designsystem.tokens.AppMotion
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppSpacing

@Composable
fun AppScaffold(
    screenKey: Any? = null,
    modalVisible: Boolean = false,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    bottomActions: (@Composable () -> Unit)? = null,
    snackbarState: AppSnackbarState? = null,
    floatingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    // A new destination gets visible navigation; reselecting the current root changes no state.
    val scrollState = remember(screenKey, touchExplorationEnabled, bottomActions != null) {
        AppNavigationBarScrollState(touchExplorationEnabled, pinned = bottomActions != null)
    }
    val hiddenFraction by animateFloatAsState(
        targetValue = if (scrollState.hidden) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (scrollState.hidden) {
                AppMotion.NavigationBarExitMillis
            } else {
                AppMotion.NavigationBarEnterMillis
            },
            easing = AppMotion.EmphasizedEasing,
        ),
        label = "navigationBarVisibility",
    )
    val layoutDirection = LocalLayoutDirection.current
    val blurRadius by animateFloatAsState(
        targetValue = if (modalVisible) AppMotion.DialogBlurRadiusPx else 0f,
        animationSpec = tween(AppMotion.DialogBlurMillis, easing = AppMotion.BlurEasing),
        label = "modalBackgroundBlur",
    )

    Scaffold(
        modifier = Modifier
            .imePadding()
            .nestedScroll(scrollState)
            .graphicsLayer {
                renderEffect = if (blurRadius > 0f) {
                    RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else {
                    null
                }
            },
        topBar = topBar,
        bottomBar = {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = size.height * hiddenFraction }
                    .focusProperties { canFocus = !scrollState.hidden }
                    .then(
                        if (scrollState.hidden) Modifier.clearAndSetSemantics {} else Modifier,
                    ),
            ) {
                // Reserve the system bottom inset even when no app chrome is supplied. Consuming
                // it here lets NavigationBar reuse this space instead of adding a second inset.
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                ) {
                    bottomActions?.invoke()
                    bottomBar()
                }
            }
        },
        snackbarHost = {
            if (snackbarState != null) {
                AppSnackbarHost(snackbarState)
            }
        },
        floatingActionButton = { floatingAction?.invoke() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding ->
        val viewportPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection),
        )
        CompositionLocalProvider(
            LocalAppContentBottomPadding provides padding.calculateBottomPadding() +
                (if (floatingAction != null) AppDimensions.FloatingActionSize + AppSpacing.Large else AppSpacing.None),
        ) {
            // Translation-only chrome: never shrink/grow the list viewport during animation.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(viewportPadding)
                    .consumeWindowInsets(viewportPadding),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        requireNotNull(context.getSystemService(AccessibilityManager::class.java))
    }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
