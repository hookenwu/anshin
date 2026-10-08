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
    fun `the records tab replaces the diary tab in the fifth slot`() {
        // 改名不改结构：仍是 5 个顶层目的地，第 4 位由 Route.Records 承接（旧 Route.Diary 已移除）。
        val records = TOP_LEVEL_DESTINATIONS.single { it.route == Route.Records }
        assertEquals(com.driezy.medlog.R.string.tab_records, records.labelRes)
        // 旧「日记」在第 4 位（0-based index 3），改名后原位承接。
        assertEquals(3, TOP_LEVEL_DESTINATIONS.indexOf(records))
    }

    @Test
    fun `todos are reachable from the overflow menu and never a bottom navigation tab`() {
        assertFalse(
            "待办列表不得成为底部 tab（入口在「更多」溢出菜单）",
            TOP_LEVEL_DESTINATIONS.any { it.route == Route.Todos },
        )
    }
}
