package dev.androidgui.core.designsystem.component

import dev.androidgui.core.designsystem.tokens.AppDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceGroupTest {
    @Test
    fun `single item receives outer corners on every side`() {
        val corners = preferenceCorners(GroupPosition.Single)

        assertEquals(AppDimensions.PreferenceGroupOuterRadius, corners.topStart)
        assertEquals(AppDimensions.PreferenceGroupOuterRadius, corners.topEnd)
        assertEquals(AppDimensions.PreferenceGroupOuterRadius, corners.bottomEnd)
        assertEquals(AppDimensions.PreferenceGroupOuterRadius, corners.bottomStart)
    }

    @Test
    fun `middle item receives inner corners on every side`() {
        val corners = preferenceCorners(GroupPosition.Middle)

        assertEquals(AppDimensions.PreferenceGroupInnerRadius, corners.topStart)
        assertEquals(AppDimensions.PreferenceGroupInnerRadius, corners.topEnd)
        assertEquals(AppDimensions.PreferenceGroupInnerRadius, corners.bottomEnd)
        assertEquals(AppDimensions.PreferenceGroupInnerRadius, corners.bottomStart)
    }
}
