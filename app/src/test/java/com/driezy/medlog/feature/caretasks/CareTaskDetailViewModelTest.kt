package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.repository.FakeCareTaskRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.caretasks.application.CareTaskCompletionUseCase
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CareTaskDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 1, 10)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val nowMs = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(nowMs), zone)

    private val repository = FakeCareTaskRepository()
    private val preferences: UserPreferencesRepository = mock {
        on { settingsFlow } doReturn flowOf(SettingsPreferences())
    }
    private val completion = CareTaskCompletionUseCase(ImmediateTransactionRunner, repository, clock)

    private fun viewModel() = CareTaskDetailViewModel(repository, completion, preferences, clock)

    private fun seedToggleTask(): Long = repository.seedTask(
        CareTask(
            careRecipientId = 1L,
            title = "吸氧",
            completionMode = CareTaskCompletionMode.TOGGLE,
            scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
            reminderTimes = "08:00,20:00",
            startDate = dayStart,
        ),
    )

    @Test
    fun `load expands today's fixed-time occurrences`() = runTest {
        val id = seedToggleTask()
        val viewModel = viewModel()

        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("吸氧", state.task?.title)
        assertEquals(listOf("08:00", "20:00"), state.occurrences.map { it.timeLabel })
        assertTrue(state.occurrences.all { it.status == null })
    }

    @Test
    fun `complete routes through the completion use case and marks the slot done`() = runTest {
        val id = seedToggleTask()
        val viewModel = viewModel()
        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()
        val slot = viewModel.uiState.value.occurrences.first().scheduledTimeMs

        viewModel.onAction(CareTaskDetailUiAction.Complete(slot))
        advanceUntilIdle()

        val log = repository.storedLogs().single()
        assertEquals(CareTaskLogStatus.DONE, log.status)
        assertEquals(CareTaskLogStatus.DONE, viewModel.uiState.value.occurrences.first().status)
    }

    @Test
    fun `skip marks the slot skipped`() = runTest {
        val id = seedToggleTask()
        val viewModel = viewModel()
        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()
        val slot = viewModel.uiState.value.occurrences.first().scheduledTimeMs

        viewModel.onAction(CareTaskDetailUiAction.Skip(slot))
        advanceUntilIdle()

        assertEquals(CareTaskLogStatus.SKIPPED, repository.storedLogs().single().status)
        assertEquals(CareTaskLogStatus.SKIPPED, viewModel.uiState.value.occurrences.first().status)
    }

    @Test
    fun `undo removes the record for the slot`() = runTest {
        val id = seedToggleTask()
        val viewModel = viewModel()
        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()
        val slot = viewModel.uiState.value.occurrences.first().scheduledTimeMs
        viewModel.onAction(CareTaskDetailUiAction.Complete(slot))
        advanceUntilIdle()

        viewModel.onAction(CareTaskDetailUiAction.Undo(slot))
        advanceUntilIdle()

        assertTrue(repository.storedLogs().isEmpty())
        assertEquals(null, viewModel.uiState.value.occurrences.first().status)
    }

    @Test
    fun `start records an in-progress duration slot`() = runTest {
        val id = repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "吸氧",
                completionMode = CareTaskCompletionMode.DURATION,
                defaultDurationMinutes = 30,
                scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
                reminderTimes = "08:00",
                startDate = dayStart,
            ),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()
        val slot = viewModel.uiState.value.occurrences.first().scheduledTimeMs

        viewModel.onAction(CareTaskDetailUiAction.Start(slot))
        advanceUntilIdle()

        val log = repository.storedLogs().single()
        assertEquals(CareTaskLogStatus.IN_PROGRESS, log.status)
        assertEquals(CareTaskLogStatus.IN_PROGRESS, viewModel.uiState.value.occurrences.first().status)
    }

    @Test
    fun `archive toggles the archived flag`() = runTest {
        val id = seedToggleTask()
        val viewModel = viewModel()
        viewModel.onAction(CareTaskDetailUiAction.Load(id))
        advanceUntilIdle()

        viewModel.onAction(CareTaskDetailUiAction.Archive)
        advanceUntilIdle()

        assertTrue(repository.storedTasks().single().isArchived)
    }
}

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}
