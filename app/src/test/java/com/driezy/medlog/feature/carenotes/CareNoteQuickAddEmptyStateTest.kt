package com.driezy.medlog.feature.carenotes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 选项 B 守护：两条规则必须**同时**成立、永不互相打架——
 *  1. 相关笔记卡片在**零笔记时整块缺席**（无 header、无占位，docs/care-notes.md §7 冻结）；
 *  2. 快捷新增是**独立的轻量文本入口**，因此空态下仍一次可达（docs/record-center-spec.md §4.4）。
 *
 * 曾经把「＋」塞进卡片 header，迫使空态也渲染 header，与 §7 冲突。此测试把二者钉在一起：
 * 卡片空了必须没，入口却必须还在、且是一下就进的文本按钮。
 */
class CareNoteQuickAddEmptyStateTest {

    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun source(path: String) = File(projectRoot, path).readText()

    private val components =
        source("app/src/main/java/com/driezy/medlog/feature/carenotes/CareNoteComponents.kt")

    private val detailScreens = listOf(
        "medications/detail/MedicationDetailScreen.kt",
        "caretasks/CareTaskDetailScreen.kt",
        "todos/CareTodoEditorScreen.kt",
    ).map { "app/src/main/java/com/driezy/medlog/feature/$it" }

    @Test
    fun `the empty card is absent while the one-tap quick-add stays reachable`() {
        // ── 事实 1：零笔记时卡片整块缺席，且不被任何开关绕过 ──
        assertTrue(
            "RelatedNotesSection 必须在 notes 为空时无条件不渲染",
            components.contains("if (notes.isEmpty()) return"),
        )
        assertFalse(
            "空态不得被 onQuickAdd 之类的开关绕过（选项 B 之前的实现）",
            components.contains("notes.isEmpty() && onQuickAdd"),
        )
        assertFalse(
            "「相关笔记」卡片不得再持有 onQuickAdd 参数，否则空态又会冒出 header",
            Regex("""fun RelatedNotesSection\([^)]*onQuickAdd""").containsMatchIn(components),
        )

        // ── 事实 2：快捷新增是独立、轻量的文本入口，且三个详情页一次点击即可进入编辑器 ──
        assertTrue(
            "快捷新增必须是独立的文本按钮（不是卡片、不带 header）",
            Regex("""fun CareNoteQuickAddButton\([\s\S]*?TextButton\(""").containsMatchIn(components),
        )
        assertTrue(
            "快捷新增文案必须是「新建相关笔记」",
            components.contains("stringResource(R.string.care_note_quick_add)"),
        )
        detailScreens.forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path 必须调用独立的 CareNoteQuickAddButton，空态下才可达",
                screen.contains("CareNoteQuickAddButton(onClick = onQuickAddNote"),
            )
            assertFalse(
                "$path 的相关笔记卡片不得再携带 onQuickAdd（卡片与入口解耦）",
                Regex("""RelatedNotesSection\([^)]*onQuickAdd""").containsMatchIn(screen),
            )
        }
    }
}
