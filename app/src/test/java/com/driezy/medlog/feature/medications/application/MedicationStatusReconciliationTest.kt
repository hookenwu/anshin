package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.FakeMedicationStatusPreferences
import com.driezy.medlog.data.repository.FakeMedicationStatusRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 两种事实分离（更正 E）——docs/medication-status-spec.md §8 / §9.3 / E13。
 *
 * 系统自动核对写 `lastAutoReconciledAtMs`；用户主动核对写 `lastUserCheckedDate`。自动通道**绝不**移动用户日期。
 */
class MedicationStatusReconciliationTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private lateinit var medications: FakeMedicationRepository
    private lateinit var statusRepo: FakeMedicationStatusRepository
    private lateinit var preferences: FakeMedicationStatusPreferences
    private lateinit var clock: MutableClock
    private lateinit var reconcile: ReconcileMedicationStatusUseCase
    private lateinit var setStatus: SetMedicationStatusUseCase

    private val transactionRunner = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        preferences = FakeMedicationStatusPreferences()
        clock = MutableClock(Instant.parse("2026-09-19T06:00:00Z"), zone)
        val activeRecipient = mock<ActiveRecipientStore>()
        whenever(activeRecipient.current()).thenReturn(1L)
        val userPreferences = mock<UserPreferencesRepository>()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        val reminders = mock<ReconcileRemindersUseCase>()
        reconcile = ReconcileMedicationStatusUseCase(
            medications,
            statusRepo,
            preferences,
            activeRecipient,
            reminders,
            clock,
        )
        setStatus = SetMedicationStatusUseCase(
            medications,
            statusRepo,
            preferences,
            userPreferences,
            activeRecipient,
            transactionRunner,
            reminders,
            clock,
        )
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "二甲双胍", doseUnit = "片"))
        statusRepo.insertEvent(
            MedicationStatusEvent(
                id = 1,
                medicationId = 1,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = LocalDate.of(2026, 9, 19).atStartOfDay(zone).toInstant().toEpochMilli(),
                recordedAtMs = 1,
            ),
        )
    }

    @Test
    fun `auto reconcile syncs the cache and writes only the system fact`() = runTest {
        reconcile.reconcile()

        assertTrue(medications.getMedicationById(1L)!!.isArchived)
        assertEquals(clock.millis(), preferences.autoReconciledAtMs(1L))
        assertNull("自动通道绝不写用户核对日期", preferences.userCheckedDate(1L))
        assertEquals("自动通道不产生状态事件", 1, statusRepo.events.size)
    }

    @Test
    fun `auto reconcile never moves the user checked date`() = runTest {
        // 用户先主动核对一次
        setStatus.userCheck(1L, MedicationStatusKind.PAUSE)
        val userDate = preferences.userCheckedDate(1L)
        val autoBefore = preferences.autoReconciledAtMs(1L)

        repeat(3) {
            clock.now = clock.now.plusSeconds(86_400)
            reconcile.reconcile()
        }

        assertEquals("用户主动核对日期不得被系统自动核对移动", userDate, preferences.userCheckedDate(1L))
        assertTrue("autoReconciledAtMs 应前进", preferences.autoReconciledAtMs(1L)!! > (autoBefore ?: 0L))
    }

    @Test
    fun `user check writes only the user fact - no event, no cache, no auto timestamp`() = runTest {
        val eventsBefore = statusRepo.events.size
        val result = setStatus.userCheck(1L, MedicationStatusKind.PAUSE)

        assertTrue(result is MedicationStatusWriteResult.UserChecked)
        assertEquals("用户核对不得产生事件", eventsBefore, statusRepo.events.size)
        assertNull("用户核对不得写系统事实", preferences.autoReconciledAtMs(1L))
        assertEquals(LocalDate.of(2026, 9, 19).toEpochDay(), preferences.userCheckedDate(1L))
    }
}
