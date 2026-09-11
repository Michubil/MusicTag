package dev.androidgui.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppElevation
import dev.androidgui.core.designsystem.tokens.AppMotion

internal enum class GroupPosition {
    Single,
    First,
    Middle,
    Last,
}

internal data class PreferenceCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
)

internal fun preferenceCorners(position: GroupPosition): PreferenceCorners {
    val outer = AppDimensions.PreferenceGroupOuterRadius
    val inner = AppDimensions.PreferenceGroupInnerRadius
    return when (position) {
        GroupPosition.Single -> PreferenceCorners(outer, outer, outer, outer)
        GroupPosition.First -> PreferenceCorners(outer, outer, inner, inner)
        GroupPosition.Middle -> PreferenceCorners(inner, inner, inner, inner)
        GroupPosition.Last -> PreferenceCorners(inner, inner, outer, outer)
    }
}

private val LocalGroupPosition = staticCompositionLocalOf { GroupPosition.Single }

class PreferenceGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: PreferenceGroupScope.() -> Unit,
) {
    val scope = remember { PreferenceGroupScope() }
    scope.items.clear()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() }.padding(
                    start = AppDimensions.HeaderStartPadding,
                    top = AppDimensions.HeaderTopPadding,
                    end = AppDimensions.HeaderEndPadding,
                    bottom = AppDimensions.HeaderBottomPadding,
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        scope.items.forEachIndexed { index, item ->
            val position = when {
                scope.items.size == 1 -> GroupPosition.Single
                index == 0 -> GroupPosition.First
                index == scope.items.lastIndex -> GroupPosition.Last
                else -> GroupPosition.Middle
            }
            CompositionLocalProvider(LocalGroupPosition provides position) {
                item()
            }
            if (index != scope.items.lastIndex) {
                Spacer(modifier = Modifier.height(AppDimensions.AdjacentCardSpacing))
            }
        }
    }
}

@Composable
internal fun PreferenceSurface(
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pressed = interactionSource?.collectIsPressedAsState()?.value == true
    val normal = preferenceCorners(LocalGroupPosition.current)
    val duration = if (pressed) AppMotion.PressShapeMillis else AppMotion.ReleaseShapeMillis
    val targetTopStart = if (pressed) AppDimensions.PreferenceGroupOuterRadius else normal.topStart
    val targetTopEnd = if (pressed) AppDimensions.PreferenceGroupOuterRadius else normal.topEnd
    val targetBottomEnd = if (pressed) AppDimensions.PreferenceGroupOuterRadius else normal.bottomEnd
    val targetBottomStart = if (pressed) AppDimensions.PreferenceGroupOuterRadius else normal.bottomStart
    val topStart by animateDpAsState(
        targetValue = targetTopStart,
        animationSpec = tween(duration, easing = AppMotion.Easing),
        label = "preferenceTopStart",
    )
    val topEnd by animateDpAsState(
        targetValue = targetTopEnd,
        animationSpec = tween(duration, easing = AppMotion.Easing),
        label = "preferenceTopEnd",
    )
    val bottomEnd by animateDpAsState(
        targetValue = targetBottomEnd,
        animationSpec = tween(duration, easing = AppMotion.Easing),
        label = "preferenceBottomEnd",
    )
    val bottomStart by animateDpAsState(
        targetValue = targetBottomStart,
        animationSpec = tween(duration, easing = AppMotion.Easing),
        label = "preferenceBottomStart",
    )
    val shape: Shape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart,
    )

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level0,
        shadowElevation = AppElevation.Level0,
        content = content,
    )
}
