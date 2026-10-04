package com.driezy.medlog.capability.reminders

import com.driezy.medlog.capability.widgets.FakeWidgetRefresher
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.data.repository.FakeLogRepository
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AndroidReminderReconcilerTest {
    private lateinit var medications: FakeMedicationRepository
    private lateinit var careRecipients: CareRecipientRepository
    private lateinit var alarms: AlarmScheduler
    private lateinit var notifications: NotificationHelper
    private lateinit var widgets: FakeWidgetRefresher
    private lateinit var reconciler: AndroidReminderReconciler

    @Before
    fun setUp() {
        medications = FakeMedicationRepository()
        careRecipients = mock()
        alarms = mock()
        notifications = mock()
        widgets = FakeWidgetRefresher()
        reconciler = AndroidReminderReconciler(
            medications,
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
    fun `full reconciliation removes projections for ids no longer in database`() = runTest {
        whenever(alarms.cancelUnattributedAlarms()).thenReturn(setOf(91L))
        whenever(careRecipients.getRecipients()).thenReturn(emptyList())

        reconciler.reconcileAll(ReminderReconcileReason.DATA_RESTORED)

        verify(notifications).cancelAllReminderNotifications(91L)
        assertEquals(1, widgets.refreshCallCount)
    }

    private fun medication(id: Long, archived: Boolean = false, asNeeded: Boolean = false) = Medication(
        id = id,
        careRecipientId = RECIPIENT_ID,
        name = "Medication $id",
        dose = 1.0,
        doseUnit = "tablet",
        isArchived = archived,
        isPRN = asNeeded,
    )

    private companion object {
        const val RECIPIENT_ID = 7L
    }
}
