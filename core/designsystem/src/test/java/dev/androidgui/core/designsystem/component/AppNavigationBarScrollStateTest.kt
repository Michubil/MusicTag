package dev.androidgui.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationBarScrollStateTest {
    @Test
    fun `upward content movement hides the bar without consuming scroll`() {
        val state = AppNavigationBarScrollState()
        val consumed = state.onPostScroll(
            consumed = Offset(0f, -20f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )

        assertTrue(state.hidden)
        assertEquals(Offset.Zero, consumed)
    }

    @Test
    fun `reversing direction reveals the bar`() {
        val state = AppNavigationBarScrollState()
        state.scroll(-20f)
        state.scroll(1f)

        assertFalse(state.hidden)
    }

    @Test
    fun `unconsumed overscroll on a short page does not hide the bar`() {
        val state = AppNavigationBarScrollState()
        state.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput)

        assertFalse(state.hidden)
    }

    @Test
    fun `horizontal scroll and unconsumed reverse overscroll do not toggle visibility`() {
        val state = AppNavigationBarScrollState()
        state.onPostScroll(Offset(100f, 0f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(state.hidden)

        state.scroll(-20f)
        state.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
        assertTrue(state.hidden)
    }

    @Test
    fun `fling movement follows the same consumed direction policy`() {
        val state = AppNavigationBarScrollState()
        state.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.SideEffect)
        assertTrue(state.hidden)

        state.onPostScroll(Offset(0f, 20f), Offset.Zero, NestedScrollSource.SideEffect)
        assertFalse(state.hidden)
    }

    @Test
    fun `touch exploration keeps navigation reachable`() {
        val state = AppNavigationBarScrollState(touchExplorationEnabled = true)
        state.scroll(-100f)

        assertFalse(state.hidden)
    }

    @Test
    fun `fixed page actions keep navigation visible through scrolling and flings`() {
        val state = AppNavigationBarScrollState(pinned = true)
        state.scroll(-100f)
        assertFalse(state.hidden)
        state.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.SideEffect)
        assertFalse(state.hidden)
        state.scroll(10f)
        assertFalse(state.hidden)
    }

    @Test
    fun `new destination starts with visible navigation`() {
        val previousDestination = AppNavigationBarScrollState()
        previousDestination.scroll(-20f)

        assertTrue(previousDestination.hidden)
        assertFalse(AppNavigationBarScrollState().hidden)
    }

    private fun AppNavigationBarScrollState.scroll(y: Float) {
        onPostScroll(Offset(0f, y), Offset.Zero, NestedScrollSource.UserInput)
    }
}
