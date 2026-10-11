package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 状态写入路径（T4）——docs/medication-status-spec.md §3 D6、§5.4、D11、§9.5、§9.6、E10。
 */
class MedicationStatusStatusWriteTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone) // 14:00 本地
    private val today = LocalDate.of(2026, 9, 19)

    private lateinit var medications: FakeMedicationRepository
    private lateinit var statusRepo: FakeMedicationStatusRepository
    private lateinit var preferences: FakeMedicationStatusPreferences
    private lateinit var activeRecipient: ActiveRecipientStore
    private lateinit var reconcile: ReconcileRemindersUseCase
    private lateinit var useCase: SetMedicationStatusUseCase

    private val transactionRunner = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        preferences = FakeMedicationStatusPreferences()
        activeRecipient = mock()
        reconcile = mock()
        whenever(activeRecipient.current()).thenReturn(1L)
        val userPreferences = mock<UserPreferencesRepository>()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        useCase = SetMedicationStatusUseCase(
            medications,
            statusRepo,
            preferences,
            userPreferences,
            activeRecipient,
            transactionRunner,
            reconcile,
            clock,
        )
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "二甲双胍", doseUnit = "片"))
    }

    private fun localStart(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `pause records a dated event and syncs the cache in the same write`() = runTest {
        val result = useCase.apply(1L, MedicationStatusKind.PAUSE, today, reason = "医生调整")

        assertTrue(result is MedicationStatusWriteResult.EventRecorded)
        assertEquals(1, statusRepo.events.size)
        val event = statusRepo.events.single()
        assertEquals(MedicationStatusKind.PAUSE, event.kind)
        assertEquals(localStart(today), event.effectiveAtMs)
        assertEquals("医生调整", event.reason)
        // 同事务直写缓存：medications.isArchived == deriveCurrentStatus
        assertTrue(medications.getMedicationById(1L)!!.isArchived)
        verify(reconcile).medication(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `back dated pause is accepted and uses the given day`() = runTest {
        val yesterday = today.minusDays(1)
        useCase.apply(1L, MedicationStatusKind.PAUSE, yesterday)
        assertEquals(localStart(yesterday), statusRepo.events.single().effectiveAtMs)
    }

    @Test
    fun `repeating the same kind is a check with no second event`() = runTest {
        useCase.apply(1L, MedicationStatusKind.PAUSE, today)
        val second = useCase.apply(1L, MedicationStatusKind.PAUSE, today)

        assertTrue(second is MedicationStatusWriteResult.UserChecked)
        assertEquals("同 kind 重复不得产生第二条事件", 1, statusRepo.events.size)
        // 只写用户事实日期
        assertEquals(today.toEpochDay(), preferences.userCheckedDate(1L))
    }

    @Test
    fun `pause then discontinue is a real change event - not a noop`() = runTest {
        useCase.apply(1L, MedicationStatusKind.PAUSE, today)
        val second = useCase.apply(1L, MedicationStatusKind.DISCONTINUE, today)

        assertTrue(second is MedicationStatusWriteResult.EventRecorded)
        assertEquals("PAUSE -> DISCONTINUE 必须新增事件", 2, statusRepo.events.size)
        assertEquals(
            MedicationStatusKind.DISCONTINUE,
            MedicationStatusDerivation.liveStatusKind(statusRepo.events, clock.millis()),
        )
    }

    @Test
    fun `resume after pause is a change event re-activating the cache`() = runTest {
        useCase.apply(1L, MedicationStatusKind.PAUSE, today)
        val result = useCase.apply(1L, MedicationStatusKind.RESUME, today)
        assertTrue(result is MedicationStatusWriteResult.EventRecorded)
        assertFalse(medications.getMedicationById(1L)!!.isArchived)
    }

    @Test
    fun `a future effective date is rejected and writes nothing`() = runTest {
        val future = today.plusDays(1)
        val thrown = runCatching { useCase.apply(1L, MedicationStatusKind.PAUSE, future) }.exceptionOrNull()
        assertTrue(thrown is FutureEffectiveDateNotAllowed)
        assertEquals(0, statusRepo.events.size)
        assertFalse(medications.getMedicationById(1L)!!.isArchived)
    }

    @Test
    fun `today is accepted but never the day after`() = runTest {
        // today 接受
        useCase.apply(1L, MedicationStatusKind.DISCONTINUE, today)
        assertEquals(1, statusRepo.events.size)
    }

    @Test
    fun `batch pause writes every event in one pass and updates every cache`() = runTest {
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "阿司匹林", doseUnit = "片"))
        val ids = medications.getAllMedicationsFor(1L).map { it.id }

        val results = useCase.applyBatch(ids, MedicationStatusKind.PAUSE, today)

        assertEquals(ids.size, results.size)
        assertTrue(results.all { it is MedicationStatusWriteResult.EventRecorded })
        assertEquals(ids.size, statusRepo.events.size)
        ids.forEach { assertTrue(medications.getMedicationById(it)!!.isArchived) }
    }

    @Test
    fun `an invalid target kind is rejected before any write`() = runTest {
        val thrown = runCatching { useCase.apply(1L, MedicationStatusKind.VOID, today) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertEquals(0, statusRepo.events.size)
        verify(reconcile, never()).medication(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
}
