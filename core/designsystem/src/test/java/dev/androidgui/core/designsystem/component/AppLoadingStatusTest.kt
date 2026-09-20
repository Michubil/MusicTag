package dev.androidgui.core.designsystem.component

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import dev.androidgui.core.designsystem.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLoadingStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun briefReadNeverShowsStatusAndLaterReadGetsItsOwnDelay() {
        val loading = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme(darkTheme = false) { if (loading.value) AppLoadingStatus("Reading", deferDisplay = true) } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Reading").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Reading").assertDoesNotExist()
        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Reading").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Reading").assertIsDisplayed()
    }

    @Test
    fun fileProcessingCanDisplayImmediately() {
        compose.setContent { AppTheme(darkTheme = false) { AppLoadingStatus("Processing") } }
        compose.onNodeWithText("Processing").assertIsDisplayed()
    }
}
