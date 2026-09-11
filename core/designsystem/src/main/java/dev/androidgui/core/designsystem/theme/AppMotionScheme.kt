package dev.androidgui.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import dev.androidgui.core.designsystem.tokens.AppMotion

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object AppMotionScheme : MotionScheme {
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.EffectsDamping, AppMotion.DefaultEffectsStiffness)

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.DefaultSpatialDamping, AppMotion.DefaultSpatialStiffness)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.EffectsDamping, AppMotion.FastEffectsStiffness)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.FastSpatialDamping, AppMotion.FastSpatialStiffness)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.EffectsDamping, AppMotion.SlowEffectsStiffness)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        spring(AppMotion.SlowSpatialDamping, AppMotion.SlowSpatialStiffness)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object AppNavigationMotionScheme : MotionScheme by AppMotionScheme {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        tween(AppMotion.NavigationIndicatorMillis, easing = AppMotion.EmphasizedEasing)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        tween(AppMotion.NavigationSelectionMillis, easing = AppMotion.Easing)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object AppSwitchMotionScheme : MotionScheme by AppMotionScheme {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        tween(AppMotion.SwitchMillis, easing = AppMotion.BlurEasing)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        tween(AppMotion.SwitchMillis, easing = AppMotion.BlurEasing)
}
