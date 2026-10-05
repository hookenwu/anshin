package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.TimePeriods
import com.driezy.medlog.data.repository.FakeCareTaskRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.ReminderReconciler
import com.driezy.medlog.domain.ReminderReconciliationQueue
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CareTaskEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMs = Instant.parse("2026-01-10T04:00:00Z").toEpochMilli() // 2026-01-10 12:00 CST
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(nowMs), zone)
    private val repository = FakeCareTaskRepository()
    private val reminderReconciler: ReminderReconciler = mock()
    private val reconcileReminders = ReconcileRemindersUseCase(
        reminderReconciler,
        mock<ReminderReconciliationQueue>(),
    )

    private fun viewModel() = CareTaskEditorViewModel(repository, clock, reconcileReminders)

    @Test
    fun `saving with a blank title is blocked with a title error`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:00"))

        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals(CareTaskValidationError.EMPTY_TITLE, viewModel.uiState.value.validationError)
        assertTrue(repository.storedTasks().isEmpty())
    }

    @Test
    fun `fixed-times schedule without any time is blocked`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("吸氧"))

        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals(CareTaskValidationError.MISSING_TIME, viewModel.uiState.value.validationError)
        assertTrue(repository.storedTasks().isEmpty())
    }

    @Test
    fun `interval schedule without hours is blocked`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("翻身"))
        viewModel.onAction(CareTaskEditorUiAction.ScheduleKindChanged(CareTaskScheduleKind.INTERVAL))
        viewModel.onAction(CareTaskEditorUiAction.IntervalHoursChanged(0))

        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals(CareTaskValidationError.MISSING_INTERVAL, viewModel.uiState.value.validationError)
        assertTrue(repository.storedTasks().isEmpty())
    }

    @Test
    fun `a valid draft is persisted through the repository`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("  吸氧  "))
        viewModel.onAction(CareTaskEditorUiAction.CategoryChanged(CareTaskCategory.RESPIRATORY))
        viewModel.onAction(CareTaskEditorUiAction.CompletionModeChanged(CareTaskCompletionMode.DURATION))
        viewModel.onAction(CareTaskEditorUiAction.DefaultMinutesChanged(30))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:00"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("20:00"))

        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        val saved = repository.storedTasks().single()
        assertEquals("吸氧", saved.title)
        assertEquals(CareTaskCategory.RESPIRATORY, saved.category)
        assertEquals(CareTaskCompletionMode.DURATION, saved.completionMode)
        assertEquals(30, saved.defaultDurationMinutes)
        assertEquals("08:00,20:00", saved.reminderTimes)
        assertNull(viewModel.uiState.value.validationError)
    }

    @Test
    fun `loading an existing task hydrates the draft`() = runTest {
        val id = repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "翻身",
                category = CareTaskCategory.MOBILITY,
                scheduleKind = CareTaskScheduleKind.INTERVAL,
                intervalHours = 2,
                frequencyType = "interval",
                frequencyInterval = 3,
                startDate = nowMs,
            ),
        )
        val viewModel = viewModel()

        viewModel.onAction(CareTaskEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertEquals("翻身", state.draft.title)
        assertEquals(CareTaskScheduleKind.INTERVAL, state.draft.scheduleKind)
        assertEquals(2, state.draft.intervalHours)
        assertEquals("interval", state.draft.frequencyType)
        assertEquals(3, state.draft.frequencyInterval)
    }

    @Test
    fun `updating an existing task keeps its identity`() = runTest {
        val id = repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "读数",
                scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
                reminderTimes = "08:00",
                startDate = nowMs,
            ),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("读数练习"))
        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        val tasks = repository.storedTasks()
        assertEquals(1, tasks.size)
        assertEquals(id, tasks.single().id)
        assertEquals("读数练习", tasks.single().title)
    }

    @Test
    fun `invalid or duplicate times are ignored`() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(CareTaskEditorUiAction.AddTime("25:00"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:70"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("not-a-time"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.draft.reminderTimes.isEmpty())

        viewModel.onAction(CareTaskEditorUiAction.AddTime("8:5"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:05"))
        advanceUntilIdle()
        assertEquals(listOf("08:05"), viewModel.uiState.value.draft.reminderTimes)
    }

    @Test
    fun `time periods toggle on and off`() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(CareTaskEditorUiAction.ToggleTimePeriod(TimePeriod.AFTER_BREAKFAST))
        advanceUntilIdle()
        assertEquals(setOf(TimePeriod.AFTER_BREAKFAST), viewModel.uiState.value.draft.timePeriods)

        viewModel.onAction(CareTaskEditorUiAction.ToggleTimePeriod(TimePeriod.AFTER_BREAKFAST))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.draft.timePeriods.isEmpty())
    }

    @Test
    fun `switching to interval clears fixed-time fields on save`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("翻身"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:00"))
        viewModel.onAction(CareTaskEditorUiAction.ToggleTimePeriod(TimePeriod.BEDTIME))
        viewModel.onAction(CareTaskEditorUiAction.ScheduleKindChanged(CareTaskScheduleKind.INTERVAL))
        viewModel.onAction(CareTaskEditorUiAction.IntervalHoursChanged(2))

        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        val saved = repository.storedTasks().single()
        assertEquals("", saved.reminderTimes)
        assertEquals("", saved.timePeriods)
        assertEquals(2, saved.intervalHours)
    }

    @Test
    fun `encoding a multi-period task round-trips through the draft`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("读数"))
        viewModel.onAction(CareTaskEditorUiAction.ToggleTimePeriod(TimePeriod.AFTER_BREAKFAST))
        viewModel.onAction(CareTaskEditorUiAction.ToggleTimePeriod(TimePeriod.BEDTIME))
        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        val saved = repository.storedTasks().single()
        assertEquals(
            setOf(TimePeriod.AFTER_BREAKFAST, TimePeriod.BEDTIME),
            TimePeriods.parse(saved.timePeriods).toSet(),
        )
    }

    @Test
    fun `delete removes the task`() = runTest {
        val id = repository.seedTask(CareTask(careRecipientId = 1L, title = "吸氧", startDate = nowMs))
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        viewModel.onAction(CareTaskEditorUiAction.Delete)
        advanceUntilIdle()

        assertTrue(repository.storedTasks().isEmpty())
    }

    @Test
    fun `saved effect is emitted after a successful save`() = runTest {
        val viewModel = viewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("吸氧"))
        viewModel.onAction(CareTaskEditorUiAction.AddTime("08:00"))
        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals(CareTaskEditorUiEffect.Saved, effect.await())
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `saving a task re-projects its reminders`() = runTest {
        val id = repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "吸氧",
                scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
                reminderTimes = "08:00",
                startDate = nowMs,
            ),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        viewModel.onAction(CareTaskEditorUiAction.TitleChanged("吸氧疗法"))
        viewModel.onAction(CareTaskEditorUiAction.Save)
        advanceUntilIdle()

        verify(reminderReconciler).reconcileCareTask(id, ReminderReconcileReason.MEDICATION_CHANGED)
    }

    @Test
    fun `archiving a task re-projects it so its alarms are cancelled`() = runTest {
        val id = repository.seedTask(
            CareTask(
                careRecipientId = 1L,
                title = "翻身",
                scheduleKind = CareTaskScheduleKind.INTERVAL,
                intervalHours = 2,
                startDate = nowMs,
            ),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareTaskEditorUiAction.LoadExisting(id))
        advanceUntilIdle()

        viewModel.onAction(CareTaskEditorUiAction.Archive)
        advanceUntilIdle()

        verify(reminderReconciler).reconcileCareTask(id, ReminderReconcileReason.MEDICATION_CHANGED)
    }
}
