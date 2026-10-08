package com.driezy.medlog.feature.todos

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepositoryImpl
import com.driezy.medlog.data.repository.FakeCareNoteDao
import com.driezy.medlog.data.repository.FakeCareTodoRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 待办编辑器：标题必填校验、新建落库、编辑回填与更新。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CareTodoEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCareTodoRepository()

    private fun viewModel() = CareTodoEditorViewModel(repository, careNoteRepository)

    private val noteDao = FakeCareNoteDao()
    private val careNoteRepository = CareNoteRepositoryImpl(
        noteDao,
        mock<ActiveRecipientStore> {
            on { recipientId } doReturn MutableStateFlow(1L)
            onBlocking { current() } doReturn 1L
        },
        Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC),
    )

    @Test
    fun `blank title is rejected and writes nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTodoEditorUiAction.TitleChanged("   "))
        viewModel.onAction(CareTodoEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals(CareTodoValidationError.EMPTY_TITLE, viewModel.uiState.value.validationError)
        assertTrue(repository.stored().isEmpty())
    }

    @Test
    fun `create saves a trimmed title with optional due date and source note`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTodoEditorUiAction.TitleChanged("  让护士看压疮风险  "))
        viewModel.onAction(CareTodoEditorUiAction.DueChanged(1_700_000_000_000L))
        viewModel.onAction(CareTodoEditorUiAction.SourceNoteChanged("  护工：骶尾处发红  "))
        viewModel.onAction(CareTodoEditorUiAction.Save)
        advanceUntilIdle()

        val stored = repository.stored().single()
        assertEquals("让护士看压疮风险", stored.title)
        assertEquals(1_700_000_000_000L, stored.dueAtMs)
        assertEquals("护工：骶尾处发红", stored.sourceNote)
        assertEquals(CareTodoStatus.OPEN, stored.status)
    }

    @Test
    fun `blank source note is stored as null`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTodoEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareTodoEditorUiAction.SourceNoteChanged("   "))
        viewModel.onAction(CareTodoEditorUiAction.Save)
        advanceUntilIdle()

        assertNull(repository.stored().single().sourceNote)
    }

    @Test
    fun `editing loads an existing todo and updates content without touching status`() = runTest {
        val id = repository.seed(
            CareTodo(
                careRecipientId = 1L,
                title = "原标题",
                status = CareTodoStatus.OPEN,
                sourceNote = "旧备注",
                createdAtMs = 1_000L,
            ),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareTodoEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        assertEquals("原标题", viewModel.uiState.value.draft.title)
        assertTrue(viewModel.uiState.value.isEditing)

        viewModel.onAction(CareTodoEditorUiAction.TitleChanged("新标题"))
        viewModel.onAction(CareTodoEditorUiAction.Save)
        advanceUntilIdle()

        val stored = repository.storedById(id)!!
        assertEquals("新标题", stored.title)
        assertEquals(CareTodoStatus.OPEN, stored.status)
        assertEquals(1_000L, stored.createdAtMs)
    }

    @Test
    fun `validation helper flags only an empty title`() {
        assertNull(validateCareTodoDraft(CareTodoDraft(title = "x")))
        assertEquals(
            CareTodoValidationError.EMPTY_TITLE,
            validateCareTodoDraft(CareTodoDraft(title = "   ")),
        )
    }
}
