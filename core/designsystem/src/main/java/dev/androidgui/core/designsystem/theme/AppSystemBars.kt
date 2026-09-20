package dev.androidgui.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Synchronize the owning window with the palette actually displayed, including dialog windows. */
@Composable
internal fun AppSystemBars() {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val darkStatusIcons = colors.background.luminance() > 0.5f
    val darkNavigationIcons = colors.surfaceContainer.luminance() > 0.5f
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.parent as? DialogWindowProvider)?.window
                ?: view.context.findActivity()?.window
            val statusFlag = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val navigationFlag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val appearance = (if (darkStatusIcons) statusFlag else 0) or
                (if (darkNavigationIcons) navigationFlag else 0)
            window?.insetsController?.setSystemBarsAppearance(appearance, statusFlag or navigationFlag)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
