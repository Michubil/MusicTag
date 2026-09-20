package dev.androidgui.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AppColorSchemesTest {
    @Test
    fun `fixed light surfaces match the frozen LibChecker palette`() {
        assertEquals(Color(0xFFFFFBFE), FixedLightColorScheme.background)
        assertEquals(Color(0xFFFFFBFE), FixedLightColorScheme.surface)
        assertEquals(Color(0xFFECE6F0), FixedLightColorScheme.surfaceContainerHigh)
        assertEquals(Color(0xFFF3EDF7), FixedLightColorScheme.surfaceContainer)
        assertEquals(Color(0xFF6D23F8), FixedLightColorScheme.primary)
    }

    @Test
    fun `fixed dark surfaces match the frozen LibChecker palette`() {
        assertEquals(Color(0xFF1C1B1E), FixedDarkColorScheme.background)
        assertEquals(Color(0xFF1C1B1E), FixedDarkColorScheme.surface)
        assertEquals(Color(0xFF2B2930), FixedDarkColorScheme.surfaceContainerHigh)
        assertEquals(Color(0xFF211F26), FixedDarkColorScheme.surfaceContainer)
        assertEquals(Color(0xFFD0BCFF), FixedDarkColorScheme.primary)
    }
}
