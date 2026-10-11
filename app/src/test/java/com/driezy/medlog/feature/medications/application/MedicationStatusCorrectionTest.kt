package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.repository.FakeMedicationRepository
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
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 误记更正（更正 C）——docs/medication-status-spec.md §2.C / §9.2 / D10。
 */
class MedicationStatusCorrectionTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone)
    private val today = LocalDate.of(2026, 9, 19)

    private lateinit var medications: FakeMedicationRepository
    private lateinit var statusRepo: FakeMedicationStatusRepository
    private lateinit var useCase: CorrectMedicationStatusEventUseCase

    private val transactionRunner = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    private fun at(date: LocalDate, hour: Int = 0) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        val userPreferences = mock<UserPreferencesRepository>()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        useCase = CorrectMedicationStatusEventUseCase(
            statusRepo,
            medications,
            userPreferences,
            transactionRunner,
            mock<ReconcileRemindersUseCase>(),
            clock,
        )
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "二甲双胍", doseUnit = "片"))
        // 初始：一条错误的 PAUSE@5 天前（id=1）
        statusRepo.insertEvent(
            MedicationStatusEvent(
                id = 1,
                medicationId = 1,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = at(today.minusDays(5)),
                recordedAtMs = 1,
            ),
        )
        medications.setArchivedCache(1L, true)
    }

    @Test
    fun `correcting a wrong effective date appends a superseding event and recomputes`() = runTest {
        val newId = useCase.correct(1L, MedicationStatusKind.PAUSE, today, reason = "生效日应为今天")

        assertEquals(2, statusRepo.events.size)
        val live = MedicationStatusDerivation.liveEvents(statusRepo.events)
        assertEquals("被取代者必须死亡", listOf(newId), live.map { it.id })
        assertFalse("被取代事件永不被读取", live.any { it.id == 1L })
        val corrected = statusRepo.getEventById(newId)!!
        assertEquals(at(today), corrected.effectiveAtMs)
        assertEquals(1L, corrected.supersedesId)
        assertEquals("原始事件行仍在（append-only）", 1L, statusRepo.getEventById(1L)!!.id)
    }

    @Test
    fun `correcting a wrong kind changes the derived kind`() = runTest {
        useCase.correct(1L, MedicationStatusKind.DISCONTINUE, today)
        assertEquals(
            MedicationStatusKind.DISCONTINUE,
            MedicationStatusDerivation.liveStatusKind(statusRepo.events, clock.millis()),
        )
    }

    @Test
    fun `voiding a duplicate reverts the derived state and keeps the voided row`() = runTest {
        // 加一条重复 RESUME（id=2），再 VOID 它
        statusRepo.insertEvent(
            MedicationStatusEvent(
                id = 2,
                medicationId = 1,
                kind = MedicationStatusKind.RESUME,
                effectiveAtMs = at(today),
                recordedAtMs = 2,
            ),
        )
        assertEquals(
            MedicationStatusKind.RESUME,
            MedicationStatusDerivation.liveStatusKind(statusRepo.events, clock.millis()),
        )

        useCase.void(2L, reason = "重复条目")

        // 回退到被作废行之前的活状态行（PAUSE@id1）
        assertEquals(
            MedicationStatusKind.PAUSE,
            MedicationStatusDerivation.liveStatusKind(statusRepo.events, clock.millis()),
        )
        // 作废行保留、被作废行保留（append-only）
        assertEquals(3, statusRepo.events.size)
        assertTrue(statusRepo.events.any { it.kind == MedicationStatusKind.VOID })
        assertTrue(statusRepo.events.any { it.id == 2L })
    }

    @Test
    fun `correction recomputes the cache in the same write`() = runTest {
        // 更正为 DISCONTINUE 仍是暂停 → 缓存 true；更正为 RESUME → 缓存 false
        useCase.correct(1L, MedicationStatusKind.RESUME, today)
        assertFalse(medications.getMedicationById(1L)!!.isArchived)
    }
}
