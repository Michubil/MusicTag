package dev.androidgui.core.designsystem.theme

import dev.androidgui.core.designsystem.tokens.AppDimensions
import dev.androidgui.core.designsystem.tokens.AppMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMotionTest {
    @Test
    fun `top level tabs retain LibChecker sequential timings`() {
        assertEquals(90, AppMotion.TabExitMillis)
        assertEquals(160, AppMotion.TabEnterMillis)
        assertEquals(20f, AppMotion.TabOffset.value, 0f)
        assertEquals(64f, AppDimensions.TopBarMinHeight.value, 0f)
    }

    @Test
    fun `emphasized path has the source endpoints and cubic join`() {
        assertEquals(0f, AppMotion.EmphasizedEasing.transform(0f), 0.0001f)
        assertEquals(0.4f, AppMotion.EmphasizedEasing.transform(0.166666f), 0.0001f)
        assertEquals(1f, AppMotion.EmphasizedEasing.transform(1f), 0.0001f)
        var previous = 0f
        for (step in 0..100) {
            val current = AppMotion.EmphasizedEasing.transform(step / 100f)
            assertTrue(current >= previous && current in 0f..1f)
            previous = current
        }
    }

    @Test
    fun `dialog and theme motions remain explicit`() {
        assertEquals(400, AppMotion.DialogEnterMillis)
        assertEquals(150, AppMotion.DialogExitMillis)
        assertEquals(350, AppMotion.DialogBlurMillis)
        assertEquals(160, AppMotion.ThemeExitMillis)
        assertEquals(260, AppMotion.ThemeEnterMillis)
        assertEquals(500, AppMotion.NavigationBarEnterMillis)
        assertEquals(400, AppMotion.NavigationBarExitMillis)
    }
}
