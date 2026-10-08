package com.driezy.medlog.feature.records

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.SymptomLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.data.repository.CareNoteRepositoryImpl
import com.driezy.medlog.data.repository.FakeCareNoteDao
import com.driezy.medlog.data.repository.FakeSymptomRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 记录中心 ViewModel：跨类型混排、SUPERSEDED 折叠但可搜（全部 + 照护笔记两模式一致）、
 * 编辑旧笔记不移动、成员隔离、`enableSymptomDiary` 关闭时身心记录面消失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = FakeCareNoteDao()
    private val symptoms = FakeSymptomRepository()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    private fun repository(activeId: Long = 1L): CareNoteRepository = CareNoteRepositoryImpl(
        dao,
        mock<ActiveRecipientStore> {
            on { recipientId } doReturn MutableStateFlow(activeId)
            onBlocking { current() } doReturn activeId
        },
        clock,
    )

    private fun viewModel(careNotes: CareNoteRepository = repository()): RecordsViewModel =
        RecordsViewModel(careNotes, symptoms)

    @Test
    fun `superseded notes stay folded by default but are returned by search in both modes`() = runTest {
        val activeId = seedNote(title = "常见问题", body = "每天两次", createdAtMs = 3_000)
        val supersededId = seedNote(
            title = "旧说法",
            body = "曾经每天三次",
            createdAtMs = 2_000,
            status = CareNoteStatus.SUPERSEDED,
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // 全部模式：默认折叠 SUPERSEDED。
        assertEquals(listOf("note:$activeId"), viewModel.uiState.value.entries.map { it.key })
        assertTrue(viewModel.uiState.value.entries.none { it.key == "note:$supersededId" })

        // 全部模式：主动搜索必须返回命中的 SUPERSEDED（展示层标「已被更新」）。
        viewModel.onAction(RecordsUiAction.QueryChanged("每天"))
        advanceUntilIdle()
        assertEquals(
            setOf("note:$activeId", "note:$supersededId"),
            viewModel.uiState.value.entries.map { it.key }.toSet(),
        )

        // 照护笔记模式：同一规则。
        viewModel.onAction(RecordsUiAction.SetMode(RecordsMode.CARE_NOTES))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.any { it.key == "note:$supersededId" })

        viewModel.onAction(RecordsUiAction.QueryChanged(""))
        advanceUntilIdle()
        assertTrue(
            "普通列表必须重新折叠 SUPERSEDED",
            viewModel.uiState.value.entries.none { it.key == "note:$supersededId" },
        )
    }

    @Test
    fun `mixed list orders by event time and editing an old note does not move it`() = runTest {
        val oldNoteId = seedNote(title = "旧笔记", body = "正文", createdAtMs = 1_000)
        val newNoteId = seedNote(title = "新笔记", body = "正文", createdAtMs = 3_000)
        symptoms.insert(SymptomLog(careRecipientId = 1L, recordedAt = 2_000, note = "中间记录"))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf("note:$newNoteId", "diary:1", "note:$oldNoteId"),
            viewModel.uiState.value.entries.map { it.key },
        )

        // 编辑最早的笔记（正文/状态）：updatedAtMs 刷新，但事件时间排序位置必须不变。
        repository().updateNote(
            id = oldNoteId,
            title = "旧笔记",
            body = "改过的正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
        )
        advanceUntilIdle()

        assertEquals(
            "编辑旧笔记不得因 updatedAtMs 跳到时间轴最前",
            listOf("note:$newNoteId", "diary:1", "note:$oldNoteId"),
            viewModel.uiState.value.entries.map { it.key },
        )
    }

    @Test
    fun `cross type search matches care note body and diary symptom text together`() = runTest {
        seedNote(title = "血压", body = "饭后测量更稳", createdAtMs = 1_000)
        symptoms.insert(SymptomLog(careRecipientId = 1L, recordedAt = 2_000, symptoms = "饭后头晕"))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(RecordsUiAction.QueryChanged("饭后"))
        advanceUntilIdle()

        assertEquals(
            setOf("note:1", "diary:1"),
            viewModel.uiState.value.entries.map { it.key }.toSet(),
        )
    }

    @Test
    fun `records are isolated per active member`() = runTest {
        // 成员 2 的笔记不得出现在成员 1 的记录中心（笔记表按当前成员过滤）。
        seedNote(title = "妈妈的笔记", body = "正文", createdAtMs = 1_000, recipientId = 2L)

        val viewModel = viewModel(repository(activeId = 1L))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(
            "不得看到其他成员的照护笔记",
            viewModel.uiState.value.entries.none { it is RecordEntry.CareNoteEntry },
        )
        assertTrue(viewModel.uiState.value.showEmpty)
    }

    @Test
    fun `turning the diary flag off clamps the mode and removes every diary surface`() = runTest {
        seedNote(title = "照护笔记", body = "正文", createdAtMs = 1_000)
        symptoms.insert(SymptomLog(careRecipientId = 1L, recordedAt = 2_000, note = "身心记录"))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.entries.size)

        viewModel.onAction(RecordsUiAction.DiaryAvailabilityChanged(false))
        advanceUntilIdle()

        assertEquals(RecordsMode.CARE_NOTES, viewModel.uiState.value.mode)
        assertEquals(listOf(RecordsMode.CARE_NOTES), viewModel.uiState.value.availableModes)
        assertTrue(viewModel.uiState.value.searchEnabled)
        assertTrue(
            "开关关闭后不得再有身心记录条目",
            viewModel.uiState.value.entries.none { it.key.startsWith("diary:") },
        )
        // 即使尝试切回身心记录模式也会被收敛。
        viewModel.onAction(RecordsUiAction.SetMode(RecordsMode.DIARY))
        advanceUntilIdle()
        assertEquals(RecordsMode.CARE_NOTES, viewModel.uiState.value.mode)
    }

    private fun seedNote(
        title: String,
        body: String,
        createdAtMs: Long,
        status: String = CareNoteStatus.ACTIVE,
        recipientId: Long = 1L,
    ): Long = dao.seed(
        CareNote(
            careRecipientId = recipientId,
            title = title,
            body = body,
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            status = status,
            createdAtMs = createdAtMs,
        ),
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
