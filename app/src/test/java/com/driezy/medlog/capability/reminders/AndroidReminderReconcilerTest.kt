package com.driezy.medlog.capability.reminders

import com.driezy.medlog.capability.widgets.FakeWidgetRefresher
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.data.repository.FakeLogRepository
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class AndroidReminderReconcilerTest {
    private lateinit var medications: FakeMedicationRepository
    private lateinit var careTasks: CareTaskRepository
    private lateinit var careRecipients: CareRecipientRepository
    private lateinit var alarms: AlarmScheduler
    private lateinit var notifications: NotificationHelper
    private lateinit var widgets: FakeWidgetRefresher
    private lateinit var reconciler: AndroidReminderReconciler

    @Before
    fun setUp() {
        medications = FakeMedicationRepository()
        careTasks = mock()
        careRecipients = mock()
        alarms = mock()
        notifications = mock()
        widgets = FakeWidgetRefresher()
        // 默认没有照护事项：既有用药用例的语义与改造前逐字一致
        runBlocking {
            whenever(careTasks.getAllTasksFor(any())).thenReturn(emptyList())
        }
        reconciler = AndroidReminderReconciler(
            medications,
            careTasks,
            careRecipients,
            FakeLogRepository(),
            alarms,
            notifications,
            widgets,
        )
    }

    @Test
    fun `active medication projection is replaced from database truth`() = runTest {
        val medication = medication(id = 1L)
        medications.addMedication(medication)

        reconciler.reconcileMedication(MedicationId(1L), ReminderReconcileReason.MEDICATION_CHANGED)

        verify(alarms).cancelAllAlarms(1L, RECIPIENT_ID)
        verify(notifications).cancelAllReminderNotifications(1L)
        verify(alarms).scheduleAllReminders(medication, null)
        assertEquals(1, widgets.refreshCallCount)
    }

    @Test
    fun `archived medication projection is removed without rescheduling`() = runTest {
        val medication = medication(id = 1L, archived = true)
        medications.addMedication(medication)

        reconciler.reconcileMedication(MedicationId(1L), ReminderReconcileReason.MEDICATION_CHANGED)

        verify(alarms).cancelAllAlarms(1L, RECIPIENT_ID)
        verify(notifications).cancelAllReminderNotifications(1L)
        verify(alarms, never()).scheduleAllReminders(medication)
    }

    @Test
    fun `dose reconciliation removes stale notification projection before rebuilding alarms`() = runTest {
        val medication = medication(id = 1L)
        medications.addMedication(medication)

        reconciler.reconcileMedication(MedicationId(1L), ReminderReconcileReason.DOSE_RECORDED)

        verify(notifications).cancelAllReminderNotifications(1L)
        verify(alarms).scheduleAllReminders(medication, null)
    }

    @Test
    fun `full reconciliation removes stale projections and schedules only active fixed plans`() = runTest {
        val active = medication(id = 1L)
        val archived = medication(id = 2L, archived = true)
        val asNeeded = medication(id = 3L, asNeeded = true)
        medications.addMedication(active)
        medications.addMedication(archived)
        medications.addMedication(asNeeded)
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        listOf(1L, 2L, 3L).forEach { id ->
            verify(notifications).cancelAllReminderNotifications(id)
        }
        // 只清理当前成员名下的登记项：不会波及其他成员的闹钟（阶段 1 的核心改动）
        verify(alarms).cancelAlarmsFor(RECIPIENT_ID)
        verify(alarms).scheduleAllReminders(active, null)
        verify(alarms, never()).scheduleAllReminders(archived, null)
        verify(alarms, never()).scheduleAllReminders(asNeeded, null)
        assertEquals(1, widgets.refreshCallCount)
    }

    @Test
    fun `a medication-only reconcile never touches the care-task pipeline`() = runTest {
        val active = medication(id = 1L)
        medications.addMedication(active)
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )

        reconciler.reconcileAll(ReminderReconcileReason.MEDICATION_CHANGED)

        verify(alarms).scheduleAllReminders(active, null)
        verify(alarms, never()).scheduleCareTaskReminders(any(), any(), any())
        verify(notifications, never()).cancelCareTaskNotifications(any())
    }

    @Test
    fun `full reconciliation removes projections for ids no longer in database`() = runTest {
        whenever(alarms.cancelUnattributedAlarms()).thenReturn(
            listOf(ReminderTarget(0L, ReminderTargetType.MEDICATION, 91L)),
        )
        whenever(careRecipients.getRecipients()).thenReturn(emptyList())

        reconciler.reconcileAll(ReminderReconcileReason.DATA_RESTORED)

        verify(notifications).cancelAllReminderNotifications(91L)
        assertEquals(1, widgets.refreshCallCount)
    }

    @Test
    fun `full reconciliation schedules care tasks for every member`() = runTest {
        val momTask = careTask(id = 21L, recipientId = RECIPIENT_ID, title = "吸氧")
        val dadTask = careTask(
            id = 22L,
            recipientId = OTHER_RECIPIENT_ID,
            title = "翻身",
            scheduleKind = CareTaskScheduleKind.INTERVAL,
            intervalHours = 2,
        )
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(
                CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈"),
                CareRecipient(id = OTHER_RECIPIENT_ID, uuid = "uuid-8", displayName = "爸爸"),
            ),
        )
        whenever(careTasks.getAllTasksFor(RECIPIENT_ID)).thenReturn(listOf(momTask))
        whenever(careTasks.getAllTasksFor(OTHER_RECIPIENT_ID)).thenReturn(listOf(dadTask))
        whenever(careTasks.getLogsForTask(any())).thenReturn(flowOf(emptyList()))

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        verify(careTasks).getAllTasksFor(RECIPIENT_ID)
        verify(careTasks).getAllTasksFor(OTHER_RECIPIENT_ID)
        verify(alarms).scheduleCareTaskReminders(momTask, null, emptySet())
        verify(alarms).scheduleCareTaskReminders(dadTask, null, emptySet())
    }

    @Test
    fun `archived and as-needed care tasks are cleaned up without scheduling`() = runTest {
        val archived = careTask(id = 31L, recipientId = RECIPIENT_ID, title = "旧事项", archived = true)
        val asNeeded = careTask(
            id = 32L,
            recipientId = RECIPIENT_ID,
            title = "按需",
            scheduleKind = CareTaskScheduleKind.AS_NEEDED,
        )
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )
        whenever(careTasks.getAllTasksFor(RECIPIENT_ID)).thenReturn(listOf(archived, asNeeded))

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        verify(notifications).cancelCareTaskNotifications(31L)
        verify(notifications).cancelCareTaskNotifications(32L)
        verify(alarms, never()).scheduleCareTaskReminders(eq(archived), any(), any())
        verify(alarms, never()).scheduleCareTaskReminders(eq(asNeeded), any(), any())
    }

    @Test
    fun `interval care task is anchored on the last DONE log actual end`() = runTest {
        val task = careTask(
            id = 41L,
            recipientId = RECIPIENT_ID,
            title = "翻身",
            scheduleKind = CareTaskScheduleKind.INTERVAL,
            intervalHours = 2,
        )
        val done = CareTaskLog(
            id = 1L,
            careTaskId = 41L,
            scheduledTimeMs = 1_000L,
            status = CareTaskLogStatus.DONE,
            actualEndMs = 5_000L,
        )
        val skipped = CareTaskLog(
            id = 2L,
            careTaskId = 41L,
            scheduledTimeMs = 2_000L,
            status = CareTaskLogStatus.SKIPPED,
        )
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )
        whenever(careTasks.getAllTasksFor(RECIPIENT_ID)).thenReturn(listOf(task))
        whenever(careTasks.getLogsForTask(41L)).thenReturn(flowOf(listOf(done, skipped)))

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        verify(alarms).scheduleCareTaskReminders(
            task,
            5_000L,
            setOf(Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(2_000L)),
        )
    }

    @Test
    fun `single care task reconciliation cancels then rebuilds the projection`() = runTest {
        val task = careTask(id = 51L, recipientId = RECIPIENT_ID, title = "读数")
        whenever(careTasks.getTaskById(51L)).thenReturn(task)
        whenever(careTasks.getLogsForTask(51L)).thenReturn(flowOf(emptyList()))

        reconciler.reconcileCareTask(51L, ReminderReconcileReason.MEDICATION_CHANGED)

        verify(alarms).cancelCareTaskAlarms(51L, RECIPIENT_ID)
        verify(notifications).cancelCareTaskNotifications(51L)
        verify(alarms).scheduleCareTaskReminders(task, null, emptySet())
    }

    @Test
    fun `reconciling a deleted care task cancels by identity so the phantom entry is reclaimed`() = runTest {
        whenever(careTasks.getTaskById(61L)).thenReturn(null)

        reconciler.reconcileCareTask(61L, ReminderReconcileReason.MEDICATION_CHANGED)

        // 行已删除 → 拿不到成员 id，必须按 (type, id) 跨成员清理，不能退化成 (0, id)
        verify(alarms).cancelAlarmsForMissingOwner(ReminderTargetType.CARE_TASK, 61L)
        verify(alarms, never()).cancelCareTaskAlarms(any(), any())
        verify(notifications).cancelCareTaskNotifications(61L)
        verify(alarms, never()).scheduleCareTaskReminders(any(), any(), any())
    }

    @Test
    fun `reconciling a deleted medication cancels by identity instead of an unknown recipient`() = runTest {
        reconciler.reconcileMedication(MedicationId(91L), ReminderReconcileReason.MEDICATION_CHANGED)

        verify(alarms).cancelAlarmsForMissingOwner(ReminderTargetType.MEDICATION, 91L)
        verify(alarms, never()).cancelAllAlarms(eq(91L), any())
        verify(notifications).cancelAllReminderNotifications(91L)
        verify(alarms, never()).scheduleAllReminders(any(), any(), any())
    }

    @Test
    fun `full reconciliation prunes orphaned projections and reclaims their notifications`() = runTest {
        val orphanTask = ReminderTarget(1L, ReminderTargetType.CARE_TASK, 1L)
        val orphanMedication = ReminderTarget(1L, ReminderTargetType.MEDICATION, 13L)
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )
        whenever(alarms.pruneOrphanedProjections(any())).thenReturn(listOf(orphanTask, orphanMedication))

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        verify(alarms).pruneOrphanedProjections(any())
        verify(notifications).cancelCareTaskNotifications(1L)
        verify(notifications).cancelAllReminderNotifications(13L)
    }

    @Test
    fun `active medication is never reported as an orphan by the self-healing prune`() = runTest {
        val active = medication(id = 1L)
        medications.addMedication(active)
        whenever(careRecipients.getRecipients()).thenReturn(
            listOf(CareRecipient(id = RECIPIENT_ID, uuid = "uuid-7", displayName = "妈妈")),
        )
        whenever(alarms.pruneOrphanedProjections(any())).thenReturn(emptyList())

        reconciler.reconcileAll(ReminderReconcileReason.SYSTEM_EVENT)

        // 存活用药照常重排登记；清理回调只对非存活目标返回 true
        verify(alarms).scheduleAllReminders(active, null)
        val predicate = org.mockito.kotlin.argumentCaptor<(ReminderTarget) -> Boolean>()
        verify(alarms).pruneOrphanedProjections(predicate.capture())
        assertTrue(
            "本轮刚排期的存活用药不能被判为孤儿",
            predicate.firstValue(ReminderTarget(RECIPIENT_ID, ReminderTargetType.MEDICATION, 1L)),
        )
        assertFalse(
            "已归档用药是孤儿",
            predicate.firstValue(ReminderTarget(RECIPIENT_ID, ReminderTargetType.MEDICATION, 2L)),
        )
        assertFalse(
            "已删除成员名下的目标一律是孤儿",
            predicate.firstValue(ReminderTarget(999L, ReminderTargetType.CARE_TASK, 1L)),
        )
    }

    private fun medication(id: Long, archived: Boolean = false, asNeeded: Boolean = false) = Medication(
        id = id,
        careRecipientId = RECIPIENT_ID,
        name = "Medication $id",
        doseUnit = "tablet",
        isArchived = archived,
        isPRN = asNeeded,
    )

    private fun careTask(
        id: Long,
        recipientId: Long,
        title: String,
        archived: Boolean = false,
        scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        intervalHours: Int = 0,
    ) = CareTask(
        id = id,
        careRecipientId = recipientId,
        title = title,
        category = CareTaskCategory.OTHER,
        scheduleKind = scheduleKind,
        reminderTimes = if (scheduleKind == CareTaskScheduleKind.FIXED_TIMES) "08:00" else "",
        intervalHours = intervalHours,
        isArchived = archived,
        startDate = 0L,
    )

    private companion object {
        const val RECIPIENT_ID = 7L
        const val OTHER_RECIPIENT_ID = 8L
    }
}
