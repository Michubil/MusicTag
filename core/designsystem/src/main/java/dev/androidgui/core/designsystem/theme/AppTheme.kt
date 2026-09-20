package dev.androidgui.core.designsystem.theme

// Music Tag adaptation: synchronize system-bar icon contrast with the displayed palette.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.androidgui.core.designsystem.tokens.AppMotion

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> FixedDarkColorScheme
            else -> FixedLightColorScheme
        }
    }

    var displayedColorScheme by remember { mutableStateOf(colorScheme) }
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(colorScheme) {
        if (displayedColorScheme !== colorScheme) {
            opacity.animateTo(0f, tween(AppMotion.ThemeExitMillis, easing = AppMotion.Easing))
            displayedColorScheme = colorScheme
        }
        if (opacity.value < 1f) {
            opacity.animateTo(1f, tween(AppMotion.ThemeEnterMillis, easing = AppMotion.Easing))
        }
    }

    MaterialExpressiveTheme(
        colorScheme = displayedColorScheme,
        motionScheme = AppMotionScheme,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        AppSystemBars()
        Box(Modifier.fillMaxSize().background(displayedColorScheme.background)) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
                content()
            }
        }
    }
}
