package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.repository.FakeCareTaskRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CareTasksViewModelTest {

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

    private fun fixedTask(title: String, archived: Boolean = false) = CareTask(
        careRecipientId = 1L,
        title = title,
        scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        reminderTimes = "08:00",
        startDate = dayStart,
        isArchived = archived,
    )

    @Test
    fun `active and archived task lists are split by the repository scopes`() = runTest {
        repository.seedTask(fixedTask("吸氧"))
        repository.seedTask(fixedTask("旧项", archived = true))
        val viewModel = CareTasksViewModel(repository, preferences, clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("吸氧"), state.visibleTasks.map { it.title })
        assertEquals(listOf("旧项"), state.archivedTasks.map { it.title })
    }

    @Test
    fun `showing archived swaps which list is visible`() = runTest {
        repository.seedTask(fixedTask("吸氧"))
        repository.seedTask(fixedTask("旧项", archived = true))
        val viewModel = CareTasksViewModel(repository, preferences, clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CareTasksUiAction.SetShowArchived(true))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showArchived)
        assertEquals(listOf("旧项"), viewModel.uiState.value.visibleTasks.map { it.title })
    }

    @Test
    fun `today status reflects the log of the current fixed-time slot`() = runTest {
        val doneId = repository.seedTask(fixedTask("吸氧"))
        val pendingId = repository.seedTask(fixedTask("翻身"))
        repository.seedLog(
            CareTaskLog(
                careTaskId = doneId,
                scheduledTimeMs = day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
                status = CareTaskLogStatus.DONE,
            ),
        )
        val viewModel = CareTasksViewModel(repository, preferences, clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val status = viewModel.uiState.value.todayStatus
        assertEquals(CareTaskTodayStatus.Kind.DONE, status.getValue(doneId).kind)
        assertEquals(CareTaskTodayStatus.Kind.PENDING, status.getValue(pendingId).kind)
    }

    @Test
    fun `interval schedule has no cheap today status`() = runTest {
        repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "翻身",
                scheduleKind = CareTaskScheduleKind.INTERVAL,
                intervalHours = 2,
                startDate = dayStart,
            ),
        )
        val viewModel = CareTasksViewModel(repository, preferences, clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.todayStatus.isEmpty())
    }
}
