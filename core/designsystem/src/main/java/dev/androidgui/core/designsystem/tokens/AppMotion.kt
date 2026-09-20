package dev.androidgui.core.designsystem.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/**
 * Frozen from LibChecker 7ea3561, Material Components 1.14.0 and Android 17's window motions.
 * Numeric/path adaptations: copyright LibChecker contributors and AOSP, Apache-2.0.
 * See the module's THIRD_PARTY_NOTICES.md for sources. No dependency motion defaults.
 */
object AppMotion {
    const val PressShapeMillis = 120
    const val ReleaseShapeMillis = 220
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val TabExitMillis = 90
    const val TabEnterMillis = 160
    val TabOffset = 20.dp
    val TabExitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val TabEnterEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    const val PageSlideMillis = 450
    const val PageFadeMillis = 83
    const val PageOpenFadeDelayMillis = 50
    const val PageCloseFadeDelayMillis = 35
    val PageOffset = 96.dp

    const val ThemeExitMillis = 160
    const val ThemeEnterMillis = 260
    const val NavigationBarEnterMillis = 500
    const val NavigationBarExitMillis = 400
    const val NavigationSelectionMillis = 400
    const val NavigationIndicatorMillis = 500
    const val SwitchMillis = 250

    const val DialogEnterMillis = 400
    const val DialogExitMillis = 150
    const val DialogBlurMillis = 350
    const val DialogStartScale = 0.8f
    const val DialogScrimAlpha = 0.32f
    const val DialogBlurRadiusPx = 64f
    val DialogEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val DialogExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.2f)
    val BlurEasing = Easing { fraction -> ((cos((fraction + 1f) * Math.PI) / 2.0) + 0.5).toFloat() }

    // The two cubic segments of m3_sys_motion_easing_emphasized, normalized per segment.
    // This is the same path without an Android graphics dependency during unit tests.
    private const val EmphasizedJoinX = 0.166666f
    private const val EmphasizedJoinY = 0.4f
    private val emphasizedStart = CubicBezierEasing(
        0.05f / EmphasizedJoinX, 0f, 0.133333f / EmphasizedJoinX, 0.06f / EmphasizedJoinY,
    )
    private val emphasizedEnd = CubicBezierEasing(
        (0.208333f - EmphasizedJoinX) / (1f - EmphasizedJoinX),
        (0.82f - EmphasizedJoinY) / (1f - EmphasizedJoinY),
        (0.25f - EmphasizedJoinX) / (1f - EmphasizedJoinX),
        1f,
    )
    val EmphasizedEasing = Easing { fraction ->
        if (fraction <= EmphasizedJoinX) {
            emphasizedStart.transform(fraction / EmphasizedJoinX) * EmphasizedJoinY
        } else {
            EmphasizedJoinY + emphasizedEnd.transform(
                (fraction - EmphasizedJoinX) / (1f - EmphasizedJoinX),
            ) * (1f - EmphasizedJoinY)
        }
    }

    const val FastSpatialDamping = 0.6f
    const val FastSpatialStiffness = 800f
    const val DefaultSpatialDamping = 0.8f
    const val DefaultSpatialStiffness = 380f
    const val SlowSpatialDamping = 0.8f
    const val SlowSpatialStiffness = 200f
    const val EffectsDamping = 1f
    const val FastEffectsStiffness = 3800f
    const val DefaultEffectsStiffness = 1600f
    const val SlowEffectsStiffness = 800f
}
