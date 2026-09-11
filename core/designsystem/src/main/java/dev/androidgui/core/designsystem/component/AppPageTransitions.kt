package dev.androidgui.core.designsystem.component

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import dev.androidgui.core.designsystem.tokens.AppMotion

enum class AppPageMotion { TabForward, TabBackward, Open, Close }

@Composable
fun rememberAppPageTransitions(): AppPageTransitions {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            AppPageTransitions(AppMotion.TabOffset.roundToPx(), AppMotion.PageOffset.roundToPx())
        }
    }
}

class AppPageTransitions internal constructor(private val tabOffset: Int, private val pageOffset: Int) {
    fun enter(motion: AppPageMotion): EnterTransition = when (motion) {
        AppPageMotion.TabForward, AppPageMotion.TabBackward -> {
            val direction = if (motion == AppPageMotion.TabForward) 1 else -1
            fadeIn(tween(AppMotion.TabEnterMillis, AppMotion.TabExitMillis, AppMotion.TabEnterEasing)) +
                slideInHorizontally(
                    tween(AppMotion.TabEnterMillis, AppMotion.TabExitMillis, AppMotion.TabEnterEasing),
                ) { direction * tabOffset }
        }
        AppPageMotion.Open -> fadeIn(
            tween(AppMotion.PageFadeMillis, AppMotion.PageOpenFadeDelayMillis, LinearEasing),
        ) + slideInHorizontally(tween(AppMotion.PageSlideMillis, easing = AppMotion.EmphasizedEasing)) {
            pageOffset
        }
        AppPageMotion.Close -> slideInHorizontally(
            tween(AppMotion.PageSlideMillis, easing = AppMotion.EmphasizedEasing),
        ) { -pageOffset }
    }

    fun exit(motion: AppPageMotion): ExitTransition = when (motion) {
        AppPageMotion.TabForward, AppPageMotion.TabBackward -> {
            val direction = if (motion == AppPageMotion.TabForward) 1 else -1
            fadeOut(tween(AppMotion.TabExitMillis, easing = AppMotion.TabExitEasing)) +
                slideOutHorizontally(tween(AppMotion.TabExitMillis, easing = AppMotion.TabExitEasing)) {
                    -direction * tabOffset
                }
        }
        AppPageMotion.Open -> slideOutHorizontally(
            tween(AppMotion.PageSlideMillis, easing = AppMotion.EmphasizedEasing),
        ) { -pageOffset }
        AppPageMotion.Close -> fadeOut(
            tween(AppMotion.PageFadeMillis, AppMotion.PageCloseFadeDelayMillis, LinearEasing),
        ) + slideOutHorizontally(tween(AppMotion.PageSlideMillis, easing = AppMotion.EmphasizedEasing)) {
            pageOffset
        }
    }
}
