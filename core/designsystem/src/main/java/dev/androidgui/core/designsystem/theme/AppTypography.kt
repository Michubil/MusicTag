package dev.androidgui.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppFontFamily = FontFamily.SansSerif

internal val AppTypography = Typography(
    displayLarge = textStyle(57, 64, FontWeight.Normal, -0.25f),
    displayMedium = textStyle(45, 52, FontWeight.Normal),
    displaySmall = textStyle(36, 44, FontWeight.Normal),
    headlineLarge = textStyle(32, 40, FontWeight.Normal),
    headlineMedium = textStyle(28, 36, FontWeight.Normal),
    headlineSmall = textStyle(24, 32, FontWeight.Normal),
    titleLarge = textStyle(22, 28, FontWeight.Medium),
    titleMedium = textStyle(16, 24, FontWeight.Medium, 0.15f),
    titleSmall = textStyle(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = textStyle(16, 24, FontWeight.Normal, 0.5f),
    bodyMedium = textStyle(14, 20, FontWeight.Normal, 0.25f),
    bodySmall = textStyle(12, 16, FontWeight.Normal, 0.4f),
    labelLarge = textStyle(14, 20, FontWeight.Medium, 0.1f),
    labelMedium = textStyle(12, 16, FontWeight.Medium, 0.5f),
    labelSmall = textStyle(11, 16, FontWeight.Medium, 0.5f),
)

private fun textStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)
