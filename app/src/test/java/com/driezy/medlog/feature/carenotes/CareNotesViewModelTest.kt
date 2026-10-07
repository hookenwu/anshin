package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.data.repository.CareNoteRepositoryImpl
import com.driezy.medlog.data.repository.CareNoteTarget
import com.driezy.medlog.data.repository.FakeCareNoteDao
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
 * 照护笔记列表页：默认过滤、搜索返回 SUPERSEDED、状态切换、悬挂提示 + 一键清除、成员隔离。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CareNotesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = FakeCareNoteDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    private fun repoFor(recipientId: Long = 1L): CareNoteRepository {
        val store: ActiveRecipientStore = mock {
            on { this.recipientId } doReturn MutableStateFlow(recipientId)
            onBlocking { current() } doReturn recipientId
        }
        return CareNoteRepositoryImpl(dao, store, clock)
    }

    @Test
    fun `default list hides superseded while search returns it`() = runTest {
        val repository = repoFor()
        val activeId = repository.createNote("常见问题", "每天两次", CareNoteAttributionType.CLINICIAN)
        val supersededId = repository.createNote("旧说法", "曾经每天三次", CareNoteAttributionType.CLINICIAN)
        repository.setStatus(supersededId, CareNoteStatus.SUPERSEDED, "现改为每天两次")

        val viewModel = CareNotesViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(activeId), viewModel.uiState.value.notes.map { it.note.id })
        assertTrue(viewModel.uiState.value.notes.none { it.note.status == CareNoteStatus.SUPERSEDED })

        viewModel.onAction(CareNotesUiAction.QueryChanged("每天"))
        advanceUntilIdle()

        val ids = viewModel.uiState.value.notes.map { it.note.id }.toSet()
        assertEquals(setOf(activeId, supersededId), ids)
        assertTrue(
            "主动搜索必须仍返回命中的 SUPERSEDED（标记「已被更新」由展示层负责）",
            viewModel.uiState.value.notes.any { it.note.status == CareNoteStatus.SUPERSEDED },
        )
    }

    @Test
    fun `status switching is user driven and reflected in the list`() = runTest {
        val repository = repoFor()
        val id = repository.createNote("观察", "有点咳嗽", CareNoteAttributionType.PERSONAL_OBSERVATION)

        val viewModel = CareNotesViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CareNoteStatus.ACTIVE, dao.storedById(id)!!.status)

        viewModel.onAction(CareNotesUiAction.MarkQuestionable(id))
        advanceUntilIdle()
        assertEquals(CareNoteStatus.QUESTIONABLE, dao.storedById(id)!!.status)

        viewModel.onAction(CareNotesUiAction.MarkSuperseded(id, "已问医生，改说法"))
        advanceUntilIdle()
        val superseded = dao.storedById(id)!!
        assertEquals(CareNoteStatus.SUPERSEDED, superseded.status)
        assertEquals("已问医生，改说法", superseded.supersededText)
        // 普通列表默认隐藏 SUPERSEDED
        assertTrue(viewModel.uiState.value.notes.none { it.note.id == id })

        viewModel.onAction(CareNotesUiAction.MarkActive(id))
        advanceUntilIdle()
        assertEquals(CareNoteStatus.ACTIVE, dao.storedById(id)!!.status)
    }

    @Test
    fun `dangling link surfaces a hint and one-tap removal keeps the note`() = runTest {
        val repository = repoFor()
        val id = repository.createNote(
            title = "笔记",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.MEDICATION, 99L)),
        )

        val viewModel = CareNotesViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val row = viewModel.uiState.value.notes.first { it.note.id == id }
        assertEquals(listOf(99L), row.danglingLinks.map { it.targetId })

        viewModel.onAction(CareNotesUiAction.RemoveDanglingLinks(id))
        advanceUntilIdle()

        assertTrue(dao.storedLinks().isEmpty())
        assertEquals("清除悬挂关联后笔记必须保留", 1, viewModel.uiState.value.notes.count { it.note.id == id })
    }

    @Test
    fun `notes are isolated per active recipient`() = runTest {
        dao.seed(note(recipientId = 2, title = "妈妈的笔记"))

        val viewModel = CareNotesViewModel(repoFor(recipientId = 1))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.notes.isEmpty())
    }

    private fun note(recipientId: Long, title: String) = CareNote(
        careRecipientId = recipientId,
        title = title,
        body = "正文",
        attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
