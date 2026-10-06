package com.driezy.medlog.feature.todos

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.data.repository.FakeCareTodoRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** 待办列表页：进行中 / 历史分流与完成/取消/重开的落库结果。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CareTodosViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCareTodoRepository()

    @Test
    fun `active and history lists are split by status`() = runTest {
        repository.seed(todo(id = 1, title = "未闭环"))
        repository.seed(todo(id = 2, title = "已完成", status = CareTodoStatus.DONE, closedAtMs = 10L))
        val viewModel = CareTodosViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1L), state.activeTodos.map { it.id })
        assertEquals(listOf(2L), state.historyTodos.map { it.id })
        assertEquals(listOf(1L), state.visibleTodos.map { it.id })
    }

    @Test
    fun `selecting history swaps the visible list`() = runTest {
        repository.seed(todo(id = 1, title = "未闭环"))
        repository.seed(todo(id = 2, title = "已完成", status = CareTodoStatus.DONE, closedAtMs = 10L))
        val viewModel = CareTodosViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CareTodosUiAction.SetTab(CareTodosTab.HISTORY))
        advanceUntilIdle()

        assertEquals(CareTodosTab.HISTORY, viewModel.uiState.value.selectedTab)
        assertEquals(listOf(2L), viewModel.uiState.value.visibleTodos.map { it.id })
    }

    @Test
    fun `complete moves the todo into history`() = runTest {
        val id = repository.seed(todo(id = 1, title = "未闭环"))
        val viewModel = CareTodosViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CareTodosUiAction.Complete(id))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(emptyList<Long>(), state.activeTodos.map { it.id })
        assertEquals(listOf(id), state.historyTodos.map { it.id })
        assertEquals(CareTodoStatus.DONE, repository.storedById(id)!!.status)
    }

    @Test
    fun `cancel moves the todo into history with CANCELLED`() = runTest {
        val id = repository.seed(todo(id = 1, title = "未闭环"))
        val viewModel = CareTodosViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CareTodosUiAction.Cancel(id))
        advanceUntilIdle()

        assertEquals(CareTodoStatus.CANCELLED, repository.storedById(id)!!.status)
        assertEquals(listOf(id), viewModel.uiState.value.historyTodos.map { it.id })
    }

    @Test
    fun `reopen returns the todo to active and clears closedAt`() = runTest {
        val id = repository.seed(todo(id = 1, title = "已完成", status = CareTodoStatus.DONE, closedAtMs = 99L))
        val viewModel = CareTodosViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CareTodosUiAction.Reopen(id))
        advanceUntilIdle()

        val stored = repository.storedById(id)!!
        assertEquals(CareTodoStatus.OPEN, stored.status)
        assertNull("撤销必须清空 closedAtMs", stored.closedAtMs)
        assertEquals(listOf(id), viewModel.uiState.value.activeTodos.map { it.id })
    }

    private fun todo(id: Long, title: String, status: String = CareTodoStatus.OPEN, closedAtMs: Long? = null) =
        CareTodo(
            id = id,
            careRecipientId = 1L,
            title = title,
            status = status,
            createdAtMs = id * 1_000L,
            closedAtMs = closedAtMs,
        )
}
