package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.local.DatabaseSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「声明了却从未接线」（dead code）守门测试——正是编辑器无限转圈的成因。
 *
 * 契约里声明的每个 `UiAction` 都必须被对应的界面真正派发；动作存在、ViewModel 也处理了，
 * 但没有任何入口能触发的，就是死代码。删除动作此前正是这种形态（`CareNotesUiAction.Delete`
 * 已定义、VM 已处理、仓库已实现，却没有任何界面派发它）。
 *
 * 另核对外键：删除笔记只允许级联 `care_note_links`（以导出的 schema JSON 为证），
 * 不得牵连任何表。
 */
class CareNoteWiringGuardTest {

    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun source(path: String) = File(projectRoot, path).readText()

    private val careNotes = "app/src/main/java/com/driezy/medlog/feature/carenotes"
    private val listScreen get() = source("$careNotes/CareNotesScreen.kt")
    private val editorScreen get() = source("$careNotes/CareNoteEditorScreen.kt")
    private val listContract get() = source("$careNotes/CareNotesContract.kt")
    private val editorContract get() = source("$careNotes/CareNoteEditorContract.kt")

    @Test
    fun `every declared list action is actually dispatched by the list screen`() {
        val unWired = declaredActions(listContract, "CareNotesUiAction")
            .filterNot { listScreen.contains("CareNotesUiAction.$it") }
        assertTrue("列表契约里声明、却从未被界面派发的动作（死代码）：$unWired", unWired.isEmpty())

        // 删除动作必须显式接线——这正是本次修复的死代码。
        assertTrue("列表界面必须派发 CareNotesUiAction.Delete", listScreen.contains("CareNotesUiAction.Delete("))
        assertTrue("列表必须提供显式删除入口", listScreen.contains("""testTag("careNoteDelete")"""))
    }

    @Test
    fun `every declared editor action is actually dispatched by the editor screen`() {
        val unWired = declaredActions(editorContract, "CareNoteEditorUiAction")
            .filterNot { editorScreen.contains("CareNoteEditorUiAction.$it") }
        assertTrue("编辑器契约里声明、却从未被界面派发的动作（死代码）：$unWired", unWired.isEmpty())

        assertTrue("编辑器必须派发 CareNoteEditorUiAction.Delete", editorScreen.contains("CareNoteEditorUiAction.Delete"))
        assertTrue("编辑器必须提供显式删除入口", editorScreen.contains("""testTag("careNoteEditorDelete")"""))
    }

    @Test
    fun `the editor surfaces failure effects instead of swallowing them`() {
        // `Failed` 效果曾被 `is CareNoteEditorUiEffect.Failed -> Unit` 静默吞掉：
        // 加载或保存失败时用户看不到任何反馈。这里在源码层钉住「必须呈现」。
        assertFalse(
            "编辑器不得再静默吞掉 Failed 效果",
            Regex("""is\s+CareNoteEditorUiEffect\.Failed\s*->\s*Unit""").containsMatchIn(editorScreen),
        )
        assertTrue(
            "编辑器必须用 App 既有的 snackbar 惯用法呈现失败",
            editorScreen.contains("is CareNoteEditorUiEffect.Failed -> snackbarHostState.showSnackbar("),
        )
        assertTrue(
            "编辑器必须把 snackbarHostState 交给 MedLogScreenScaffold，否则提示无处呈现",
            editorScreen.contains("snackbarHostState = snackbarHostState"),
        )
    }

    @Test
    fun `both delete affordances go through the shared destructive confirmation idiom`() {
        // 破坏性操作必须走 App 既有的 ScreenOverlayHost + ScreenOverlay.Confirm(isDanger)。
        listOf(listScreen to "列表", editorScreen to "编辑器").forEach { (src, label) ->
            assertTrue("$label 删除必须使用 ScreenOverlayHost 确认", src.contains("ScreenOverlayHost("))
            assertTrue("$label 删除必须使用 ScreenOverlay.Confirm", src.contains("ScreenOverlay.Confirm("))
            assertTrue("$label 删除确认必须标红（isDanger）", src.contains("isDanger = true"))
        }
        // 旧的 SupersedeDialog 不复用于删除（它不是破坏性确认惯用法）。
        assertTrue(listScreen.contains("care_note_delete_title"))
    }

    @Test
    fun `deleting a note only cascades its links and references no other table`() {
        val linkFks = foreignKeysOf("care_note_links")
        assertEquals("care_note_links 必须恰好一个外键", 1, linkFks.size)
        val fk = linkFks.first().jsonObject
        assertEquals("care_notes", fk["table"]!!.jsonPrimitive.content)
        assertEquals("删除笔记必须级联", "CASCADE", fk["onDelete"]!!.jsonPrimitive.content)
        assertEquals(listOf("noteId"), fk["columns"]!!.jsonArray.map { it.jsonPrimitive.content })

        // care_notes 只挂在 care_recipients 上：删除一条笔记不会波及任何其他表。
        assertEquals(
            listOf("care_recipients"),
            foreignKeysOf("care_notes").map { it.jsonObject["table"]!!.jsonPrimitive.content },
        )
    }

    /** 解析契约文件里 `: <interface>` 声明的动作子类型名。 */
    private fun declaredActions(contract: String, interfaceName: String): List<String> =
        Regex("""data (?:class|object)\s+(\w+)[^\n]*:\s*$interfaceName\b""")
            .findAll(contract)
            .map { it.groupValues[1] }
            .toList()

    private fun foreignKeysOf(entityTable: String) = schemaEntity(entityTable)["foreignKeys"]!!.jsonArray

    private fun schemaEntity(entityTable: String) = schemaDatabase()["entities"]!!.jsonArray
        .map { it.jsonObject }
        .first { it["tableName"]!!.jsonPrimitive.content == entityTable }

    private fun schemaDatabase() = Json.parseToJsonElement(
        source(
            "core/database/schemas/com.driezy.medlog.data.local.MedLogDatabase/${DatabaseSchema.VERSION}.json",
        ),
    ).jsonObject["database"]!!.jsonObject
}
