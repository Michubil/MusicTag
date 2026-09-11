package dev.androidgui.core.designsystem.component

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.androidgui.core.designsystem.icon.AppIcons
import dev.androidgui.core.designsystem.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w361dp-h794dp")
class AppScaffoldInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: View
    private var density = 1f
    private var navigationInset = 0
    private var imeInset = 0
    private var contentBottomPadding = 0f

    @Test
    fun emptyChromePreservesGestureAndThreeButtonNavigationClearance() {
        setContent(actions = false, navigation = false)
        for (bottom in listOf(24, 48)) {
            applyInsets(navigationDp = bottom)
            assertEquals(bottom * density, contentBottomPadding, 1f)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
            compose.onNodeWithText("Row 39").assertIsDisplayed()
            val last = compose.onNodeWithText("Row 39").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(last.bottom <= viewport.bottom - navigationInset)
        }
    }

    @Test
    fun actionsOnlyClearNavigationAndKeyboardWithoutAnExtraGap() {
        setContent(actions = true, navigation = false)
        for (keyboard in listOf(0, 300, 0)) {
            applyInsets(navigationDp = 48, imeDp = keyboard)
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val actions = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
            val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
            val safeBottom = viewport.bottom - maxOf(navigationInset, imeInset)
            assertEquals(safeBottom, actions.bottom, 1f)
            assertTrue(save.bottom <= safeBottom)
            assertEquals(actions.height + (if (keyboard == 0) navigationInset else 0), contentBottomPadding, 1f)
        }
    }

    @Test
    fun navigationOnlyCountsTheSystemInsetOnce() {
        assertNavigationInsets(actions = false)
    }

    @Test
    fun combinedActionsAndNavigationCountTheSystemInsetOnce() {
        assertNavigationInsets(actions = true)
    }

    private fun assertNavigationInsets(actions: Boolean) {
        setContent(actions = actions, navigation = true)
        applyInsets(navigationDp = 0)
        val basePadding = contentBottomPadding
        val barHeight = compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot.height
        for (bottom in listOf(24, 48)) {
            applyInsets(navigationDp = bottom)
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot
            assertEquals(barHeight, bar.height, 1f)
            assertEquals(viewport.bottom - navigationInset, bar.bottom, 1f)
            assertEquals(basePadding + navigationInset, contentBottomPadding, 1f)
            if (actions) {
                val actionBar = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
                assertEquals(bar.top, actionBar.bottom, 1f)
            }
        }
    }

    private fun setContent(actions: Boolean, navigation: Boolean) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val currentView = LocalView.current
            val currentDensity = LocalDensity.current
            val navigationBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(currentDensity)
            val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                view = currentView
                density = currentDensity.density
                navigationInset = navigationBottom
                imeInset = imeBottom
            }
            AppTheme(false, false) {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    AppScaffold(
                        bottomActions = if (actions) {
                            {
                                Box(Modifier.testTag("actions")) {
                                    AppActionBar("Save", "Cancel", {}, {}, true)
                                }
                            }
                        } else null,
                        bottomBar = {
                            if (navigation) {
                                Box(Modifier.testTag("navigation")) {
                                    AppNavigationBar(listOf(AppNavigationDestination("Files", AppIcons.Empty)), 0, {})
                                }
                            }
                        },
                    ) {
                        val bottom = with(currentDensity) { LocalAppContentBottomPadding.current.toPx() }
                        SideEffect { contentBottomPadding = bottom }
                        AppContentList {
                            items(40) { Text("Row $it", Modifier.height(64.dp)) }
                        }
                    }
                }
            }
        }
    }

    private fun applyInsets(navigationDp: Int, imeDp: Int = 0) {
        val navigationPixels = (navigationDp * density).toInt()
        val imePixels = (imeDp * density).toInt()
        compose.runOnIdle {
            view.dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigationPixels))
                    .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigationPixels))
                    .setVisible(WindowInsets.Type.navigationBars(), navigationPixels > 0)
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imePixels))
                    .setVisible(WindowInsets.Type.ime(), imePixels > 0)
                    .build(),
            )
        }
        compose.waitForIdle()
        assertEquals("Navigation inset delivered to Compose", navigationPixels, navigationInset)
        assertEquals("IME inset delivered to Compose", imePixels, imeInset)
    }
}
