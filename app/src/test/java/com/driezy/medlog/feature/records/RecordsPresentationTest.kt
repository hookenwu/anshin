package com.driezy.medlog.feature.records

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.SymptomLog
import com.driezy.medlog.data.repository.CareNoteWithState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录中心纯逻辑守护（docs/record-center-spec.md §3 D3/D4/D6、§8 验收 1–4）：
 * 事件时间混排、`updatedAtMs` 不参与排序、跨类型搜索、SUPERSEDED 折叠但可搜、
 * 单类型模式保留既有排序、模式可用性与 ＋ 去向。
 */
class RecordsPresentationTest {

    // ── 混排 ────────────────────────────────────────────────────────────────

    @Test
    fun `all mode interleaves the two record types by event time descending`() {
        val note = noteEntry(id = 1, createdAtMs = 5_000)
        val olderDiary = diaryEntry(id = 1, recordedAt = 1_000)
        val newerDiary = diaryEntry(id = 2, recordedAt = 9_000)

        val ordered = RecordCenterPresentation.entries(
            RecordsMode.ALL,
            query = "",
            notes = listOf(note),
            logs = listOf(olderDiary, newerDiary),
        )

        assertEquals(listOf("diary:2", "note:1", "diary:1"), ordered.map { it.key })
    }

    @Test
    fun `editing an old note never moves it in the mixed list`() {
        // A 创建更早，但后来被编辑（updatedAtMs 刷新）；B 创建更晚且从未编辑。
        val editedOld = noteEntry(id = 1, createdAtMs = 1_000, updatedAtMs = 9_999)
        val untouchedNew = noteEntry(id = 2, createdAtMs = 2_000, updatedAtMs = null)
        val middleDiary = diaryEntry(id = 1, recordedAt = 1_500)

        val ordered = RecordCenterPresentation.entries(
            RecordsMode.ALL,
            query = "",
            notes = listOf(editedOld, untouchedNew),
            logs = listOf(middleDiary),
        )

        // 排序只看 createdAtMs：B(2000) > diary(1500) > A(1000)，编辑 A 不能把它顶到最前。
        assertEquals(listOf("note:2", "diary:1", "note:1"), ordered.map { it.key })
    }

    @Test
    fun `same event time breaks ties by type then id descending`() {
        val at = 7_000L
        val note = noteEntry(id = 3, createdAtMs = at)
        val diaryLow = diaryEntry(id = 1, recordedAt = at)
        val diaryHigh = diaryEntry(id = 5, recordedAt = at)

        val ordered = RecordCenterPresentation.entries(
            RecordsMode.ALL,
            query = "",
            notes = listOf(note),
            logs = listOf(diaryLow, diaryHigh),
        )

        // 照护笔记优先，再 id 倒序。
        assertEquals(listOf("note:3", "diary:5", "diary:1"), ordered.map { it.key })
    }

    // ── 跨类型搜索 ──────────────────────────────────────────────────────────

    @Test
    fun `cross type search matches diary note symptoms and side effects`() {
        val byNote = diaryEntry(id = 1, note = "饭后有点头晕")
        val bySymptom = diaryEntry(id = 2, symptoms = "头痛,恶心")
        val bySideEffect = diaryEntry(id = 3, sideEffects = "胃部不适")
        val logs = listOf(byNote, bySymptom, bySideEffect)

        assertEquals(
            listOf("diary:3"),
            RecordCenterPresentation.entries(RecordsMode.ALL, "胃部", emptyList(), logs).map { it.key },
        )
        assertEquals(
            listOf("diary:1", "diary:2"),
            RecordCenterPresentation.entries(RecordsMode.ALL, "头", emptyList(), logs).map { it.key }
                .sorted(),
        )
        assertTrue(RecordCenterPresentation.entries(RecordsMode.ALL, "不存在的词", emptyList(), logs).isEmpty())
    }

    @Test
    fun `care notes already filtered by the repository stay in the mixed result`() {
        // 仓库在关键词非空时会返回命中的 SUPERSEDED（展示层标「已被更新」）；纯逻辑不二次过滤。
        val matched = noteEntry(id = 1, createdAtMs = 3_000, status = CareNoteStatus.SUPERSEDED)

        val ordered = RecordCenterPresentation.entries(RecordsMode.ALL, "关键词", listOf(matched), emptyList())

        assertEquals(listOf("note:1"), ordered.map { it.key })
    }

    // ── 单类型模式 ──────────────────────────────────────────────────────────

    @Test
    fun `care notes mode keeps the repository order verbatim and shows no diary rows`() {
        val first = noteEntry(id = 9, createdAtMs = 100)
        val second = noteEntry(id = 2, createdAtMs = 900)
        val third = noteEntry(id = 7, createdAtMs = 500)

        val ordered = RecordCenterPresentation.entries(
            RecordsMode.CARE_NOTES,
            query = "",
            notes = listOf(first, second, third),
            logs = listOf(diaryEntry(id = 1, recordedAt = 9_999)),
        )

        // 既有排序（QUESTIONABLE > ACTIVE > SUPERSEDED，updatedAtMs 倒序）由仓库/DAO 产出，原样保留。
        assertEquals(listOf("note:9", "note:2", "note:7"), ordered.map { it.key })
    }

    @Test
    fun `diary mode shows every diary entry and applies no query filter`() {
        val logs = listOf(diaryEntry(id = 1, note = "头晕"), diaryEntry(id = 2, note = "无事"))

        val ordered = RecordCenterPresentation.entries(
            RecordsMode.DIARY,
            query = "头晕",
            notes = listOf(noteEntry(id = 1, createdAtMs = 1)),
            logs = logs,
        )

        // 身心记录模式不新增任何筛选：关键词被忽略，照护笔记不出现。
        assertEquals(listOf("diary:1", "diary:2"), ordered.map { it.key })
    }

    // ── 模式可用性 / ＋ 去向 / 空态 ─────────────────────────────────────────

    @Test
    fun `flag off hides every diary mode while keeping care notes reachable`() {
        assertEquals(
            listOf(RecordsMode.ALL, RecordsMode.DIARY, RecordsMode.CARE_NOTES),
            availableRecordsModes(diaryAvailable = true),
        )
        assertEquals(listOf(RecordsMode.CARE_NOTES), availableRecordsModes(diaryAvailable = false))

        val flagOff = RecordsUiState(diaryAvailable = false, mode = RecordsMode.CARE_NOTES)
        assertEquals(listOf(RecordsMode.CARE_NOTES), flagOff.availableModes)
        assertFalse("开关关闭时不得出现身心记录模式", flagOff.availableModes.contains(RecordsMode.DIARY))
    }

    @Test
    fun `the plus destination follows the current mode`() {
        assertEquals(RecordsAddTarget.TYPE_CHOICE, recordsAddTarget(RecordsMode.ALL))
        assertEquals(RecordsAddTarget.DIARY_EDITOR, recordsAddTarget(RecordsMode.DIARY))
        assertEquals(RecordsAddTarget.CARE_NOTE_EDITOR, recordsAddTarget(RecordsMode.CARE_NOTES))
    }

    @Test
    fun `the diary mode offers no search box`() {
        assertFalse(RecordsUiState(mode = RecordsMode.DIARY).searchEnabled)
        assertTrue(RecordsUiState(mode = RecordsMode.ALL).searchEnabled)
        assertTrue(RecordsUiState(mode = RecordsMode.CARE_NOTES).searchEnabled)
    }

    @Test
    fun `empty state only when loaded and there are no entries`() {
        assertTrue(RecordsUiState(isLoading = false, entries = emptyList()).showEmpty)
        assertFalse(RecordsUiState(isLoading = true, entries = emptyList()).showEmpty)
        assertFalse(RecordsUiState(isLoading = false, failed = true, entries = emptyList()).showEmpty)
        assertFalse(
            RecordsUiState(
                isLoading = false,
                entries = listOf(RecordEntry.DiaryEntry(diaryEntry(id = 1, recordedAt = 1))),
            ).showEmpty,
        )
    }

    @Test
    fun `care notes mode is the only mode that keeps the existing care note ordering`() {
        assertTrue(RecordCenterPresentation.keepsExistingCareNoteOrder(RecordsMode.CARE_NOTES))
        assertFalse(RecordCenterPresentation.keepsExistingCareNoteOrder(RecordsMode.ALL))
        assertFalse(RecordCenterPresentation.keepsExistingCareNoteOrder(RecordsMode.DIARY))
    }

    // ── 构造 ────────────────────────────────────────────────────────────────

    private fun noteEntry(
        id: Long,
        createdAtMs: Long,
        updatedAtMs: Long? = null,
        status: String = CareNoteStatus.ACTIVE,
    ): CareNoteWithState = CareNoteWithState(
        note = CareNote(
            id = id,
            careRecipientId = 1L,
            title = "标题$id",
            body = "正文$id",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            status = status,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
        ),
        danglingLinks = emptyList(),
    )

    private fun diaryEntry(
        id: Long,
        recordedAt: Long = 0L,
        note: String = "",
        symptoms: String = "",
        sideEffects: String = "",
    ): SymptomLog = SymptomLog(
        id = id,
        careRecipientId = 1L,
        recordedAt = recordedAt,
        note = note,
        symptoms = symptoms,
        sideEffects = sideEffects,
    )
}
