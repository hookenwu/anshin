package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.ui.navigation.Route
import com.driezy.medlog.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守护：照护笔记是**新增**能力，不得改变既有导航层级或既有实体语义
 * （docs/care-notes.md §7/§11：不加第六个底部 tab、首页不新增区块）。
 */
class CareNoteRegressionTest {

    @Test
    fun `care notes are reachable from the overflow menu and never a bottom navigation tab`() {
        // 顶部导航仍恰好 5 个（NavigationHierarchyTest 钉死），照护笔记不是其中之一。
        assertEquals(5, TOP_LEVEL_DESTINATIONS.size)
        assertFalse(
            "照护笔记不得成为底部 tab（入口在「更多」溢出菜单）",
            TOP_LEVEL_DESTINATIONS.any { it.route == Route.CareNotes },
        )
        assertFalse(TOP_LEVEL_DESTINATIONS.any { it.route == Route.Todos })
        assertFalse(TOP_LEVEL_DESTINATIONS.any { it.route == Route.CareTasks })
    }

    @Test
    fun `existing care entities keep their state machines untouched`() {
        // 待办三态保持不变（照护笔记的三态是独立定义，不与之混用）。
        assertEquals(listOf("OPEN", "DONE", "CANCELLED"), CareTodoStatus.all)
        assertEquals(listOf("DONE", "CANCELLED"), CareTodoStatus.closed)
        assertTrue(Route.CareNoteEditor(noteId = 5L) is Route)
        assertTrue(Route.CareNotes is Route)
    }
}
