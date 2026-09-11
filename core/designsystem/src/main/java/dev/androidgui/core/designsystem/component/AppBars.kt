package dev.androidgui.core.designsystem.component

// Music Tag adaptation: semantic toolbar menu actions; frozen bar metrics and motion retained.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import dev.androidgui.core.designsystem.R
import dev.androidgui.core.designsystem.icon.AppIcon
import dev.androidgui.core.designsystem.icon.AppIconView
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppElevation
import dev.androidgui.core.designsystem.tokens.AppIconSize
import dev.androidgui.core.designsystem.theme.AppNavigationMotionScheme

@Immutable
data class AppSearchState(val query: String, val placeholder: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    menuItems: List<AppMenuItem> = emptyList(),
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onMenuItemClick: (String) -> Unit = {},
    actions: List<AppMenuItem> = emptyList(),
    onActionClick: (String) -> Unit = {},
    search: AppSearchState? = null,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchClose: () -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TopAppBar(
        title = {
            if (search != null) AppSearchField(search, onSearchQueryChange)
            else Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        expandedHeight = AppDimensions.TopBarMinHeight,
        actions = {
            actions.forEach { item ->
                IconButton(onClick = { onActionClick(item.id) }, enabled = item.enabled) {
                    AppIconView(item.icon, item.label, Modifier.size(AppIconSize.Standard))
                }
            }
            if (search != null) {
                IconButton(onClick = onSearchClose) {
                    AppIconView(AppIcons.Close, stringResource(R.string.app_close_search), Modifier.size(AppIconSize.Standard))
                }
            }
            if (menuItems.isNotEmpty()) {
                Box {
                    IconButton(onClick = { onMenuExpandedChange(true) }) {
                        AppIconView(AppIcons.Menu, stringResource(R.string.app_menu), Modifier.size(AppIconSize.Standard))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                        menuItems.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                leadingIcon = { AppIconView(item.icon, null, Modifier.size(AppIconSize.Standard)) },
                                enabled = item.enabled,
                                onClick = { onMenuItemClick(item.id) },
                            )
                        }
                    }
                }
            }
        },
        colors = colors,
    )
}

@Composable
private fun AppSearchField(search: AppSearchState, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = search.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = search.placeholder },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { inner ->
            Box {
                if (search.query.isEmpty()) {
                    Text(search.placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Immutable
data class AppMenuItem(val id: String, val label: String, val icon: AppIcon, val enabled: Boolean = true)

@Immutable
data class AppNavigationDestination(
    val label: String,
    val icon: AppIcon,
    val badge: String? = null,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppNavigationBar(
    destinations: List<AppNavigationDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        motionScheme = AppNavigationMotionScheme,
    ) {
        AppNavigationBarContent(destinations, selectedIndex, onDestinationSelected)
    }
}

@Composable
private fun AppNavigationBarContent(
    destinations: List<AppNavigationDestination>,
    selectedIndex: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.heightIn(min = AppDimensions.NavigationBarHeight),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level0,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        destinations.forEachIndexed { index, destination ->
            NavigationBarItem(
                modifier = Modifier.semantics { contentDescription = destination.label },
                selected = selectedIndex == index,
                onClick = { onDestinationSelected(index) },
                icon = {
                    BadgedBox(
                        badge = {
                            destination.badge?.let { badge ->
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ) {
                                    Text(text = badge)
                                }
                            }
                        },
                    ) {
                        AppIconView(
                            icon = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(AppIconSize.Standard),
                        )
                    }
                },
                label = {
                    Text(
                        text = destination.label,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
            )
        }
    }
}
