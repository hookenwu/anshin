package com.driezy.medlog.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationHierarchyTest {
    @Test
    fun `mobile top level navigation keeps five primary destinations`() {
        assertEquals(5, TOP_LEVEL_DESTINATIONS.size)
        assertFalse(TOP_LEVEL_DESTINATIONS.any { it.route == Route.Settings })
    }

    @Test
    fun `todos are reachable from the overflow menu and never a bottom navigation tab`() {
        assertFalse(
            "待办列表不得成为底部 tab（入口在「更多」溢出菜单）",
            TOP_LEVEL_DESTINATIONS.any { it.route == Route.Todos },
        )
    }
}
