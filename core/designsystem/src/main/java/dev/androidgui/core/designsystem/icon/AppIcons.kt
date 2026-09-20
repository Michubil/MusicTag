package dev.androidgui.core.designsystem.icon

// Music Tag adaptation: additional semantic icons for file and metadata actions.

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.androidgui.core.designsystem.R

@Immutable
@JvmInline
value class AppIcon internal constructor(@DrawableRes internal val resourceId: Int)

object AppIcons {
    val About = AppIcon(R.drawable.ic_about)
    val Album = AppIcon(R.drawable.ic_album)
    val Grid = AppIcon(R.drawable.ic_grid)
    val Settings = AppIcon(R.drawable.ic_settings)
    val Appearance = AppIcon(R.drawable.ic_palette)
    val Check = AppIcon(R.drawable.ic_check)
    val Error = AppIcon(R.drawable.ic_error)
    val Empty = AppIcon(R.drawable.ic_empty)
    val Music = AppIcon(R.drawable.ic_music)
    val Folder = AppIcon(R.drawable.ic_folder)
    val Edit = AppIcon(R.drawable.ic_edit)
    val Tag = AppIcon(R.drawable.ic_tag)
    val AutoTag = AppIcon(R.drawable.ic_auto_tag)
    val Close = AppIcon(R.drawable.ic_close)
    val Menu = AppIcon(R.drawable.ic_menu)
    val Sort = AppIcon(R.drawable.ic_sort)
    val Lyrics = AppIcon(R.drawable.ic_lyrics)
    val Search = AppIcon(R.drawable.ic_search)
}

@Composable
internal fun AppIconView(
    icon: AppIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(icon.resourceId),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
