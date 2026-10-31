package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.MedicationDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.IllegalTimeOffsetException
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanWriteDecision
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.data.model.decidePlanWrite
import com.driezy.medlog.data.model.gatedFor
import com.driezy.medlog.data.model.materializedFor
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * B4：编辑器保存路径的闸门在**数据层**（docs/medication-time-offset.md §5.1 / PLAN B4）。
 *
 * 这里用真实 [MedicationRepositoryImpl] + 假事务 + mock DAO：
 * - 非法偏移 ⇒ `IllegalTimeOffsetException` 且**没有任何 DAO 写调用**（不是钳位、不是静默丢弃）；
 * - 合法偏移 ⇒ 恰一次 `updatePlan`，且 `decidePlanWrite`（生产判定，DAO 同一个函数）判定为 **1 条修订**；
 * - 内容不变的保存 ⇒ `decidePlanWrite` 判定 Unchanged ⇒ **0 条修订**；
 * - 精确时间 / PRN 行上的非 0 偏移 ⇒ `NOT_APPLICABLE`，同样零写入。
 */
class MedicationRepositoryGateTest {

    private val routine = RoutineSchedule(bed = RoutineTime(23, 30))

    private val dao: MedicationDao = mock()
    private val preferences: ReminderPreferences = mock()
    private val activeRecipient: ActiveRecipientStore = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)

    private val transactions = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    @org.junit.Before
    fun setUp() {
        kotlinx.coroutines.runBlocking {
            whenever(activeRecipient.current()).thenReturn(RECIPIENT)
            whenever(preferences.routineScheduleFor(any())).thenReturn(routine)
            whenever(dao.insertMedication(any())).thenReturn(1L)
        }
    }

    private fun repository() = MedicationRepositoryImpl(dao, transactions, activeRecipient, clock, preferences)

    @Test
    fun `adding a bedtime medication with a crossing offset is rejected before any write`() = runTest {
        val row = medication(timePeriod = "bedtime", times = "23:30", offset = 30)

        val error = runCatching { repository().addMedication(row) }.exceptionOrNull()

        assertTrue("必须是定义好的拒绝类型，实际：$error", error is IllegalTimeOffsetException)
        assertEquals(
            TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
            (error as IllegalTimeOffsetException).check.reason,
        )
        verify(dao, never()).insertMedication(any())
        verifyBlocking(preferences) { routineScheduleFor(RECIPIENT) }
    }

    @Test
    fun `editing to an illegal offset is rejected before updatePlan`() = runTest {
        val row = medication(timePeriod = "bedtime", times = "23:30", offset = 45)

        val error = runCatching { repository().updateMedication(row) }.exceptionOrNull()

        assertTrue("必须是定义好的拒绝类型，实际：$error", error is IllegalTimeOffsetException)
        verify(dao, never()).updatePlan(any(), any())
    }

    @Test
    fun `an exact time row cannot carry a non zero offset`() = runTest {
        val row = medication(timePeriod = "", times = "09:00", offset = 15)

        val error = runCatching { repository().updateMedication(row) }.exceptionOrNull()

        assertTrue("精确时间行 +15 必须被拒（NOT_APPLICABLE），实际：$error", error is IllegalTimeOffsetException)
        assertEquals(TimeOffsetCheck.Reason.NOT_APPLICABLE, (error as IllegalTimeOffsetException).check.reason)
        verify(dao, never()).updatePlan(any(), any())
    }

    @Test
    fun `a prn row cannot carry a non zero offset`() = runTest {
        val row = medication(timePeriod = "afterBreakfast", times = "08:15", offset = 15, isPRN = true)

        val error = runCatching { repository().addMedication(row) }.exceptionOrNull()

        assertTrue("PRN 行 +15 必须被拒", error is IllegalTimeOffsetException)
        verify(dao, never()).insertMedication(any())
    }

    @Test
    fun `a legal add writes the materialized times and the offset`() = runTest {
        val row = medication(timePeriod = "afterBreakfast", times = "08:15", offset = 15)

        val id = repository().addMedication(row)

        assertEquals(1L, id)
        val written = org.mockito.kotlin.argumentCaptor<Medication>()
        verify(dao).insertMedication(written.capture())
        assertEquals("08:30", written.firstValue.reminderTimes)
        assertEquals(15, written.firstValue.offsetMinutes)
        assertEquals(8, written.firstValue.reminderHour)
        assertEquals(30, written.firstValue.reminderMinute)
    }

    @Test
    fun `a legal edit goes through updatePlan exactly once and creates one revision`() = runTest {
        val stored = medication(timePeriod = "afterBreakfast", times = "08:15", offset = 0, effectiveFrom = 1_000L)
        val revisions = mutableListOf<Medication>()
        whenever(dao.updatePlan(any(), any())).thenAnswer { invocation ->
            val next: Medication = invocation.getArgument(0)
            when (val decision = decidePlanWrite(stored, next, clock.millis())) {
                is PlanWriteDecision.Advance -> {
                    revisions += next
                    com.driezy.medlog.data.model.PlanWriteResult.Advanced
                }
                is PlanWriteDecision.Unchanged -> com.driezy.medlog.data.model.PlanWriteResult.NoOp
            }
        }

        repository().updateMedication(stored.copy(offsetMinutes = 15, reminderTimes = "08:30"))

        verify(dao).updatePlan(any(), any())
        assertEquals("内容变化 ⇒ 恰一条修订", 1, revisions.size)
        assertEquals(15, revisions.single().offsetMinutes)
    }

    @Test
    fun `an unchanged save creates no revision`() = runTest {
        val stored = medication(timePeriod = "afterBreakfast", times = "08:15", offset = 0, effectiveFrom = 1_000L)
        val revisions = mutableListOf<Medication>()
        whenever(dao.updatePlan(any(), any())).thenAnswer { invocation ->
            val next: Medication = invocation.getArgument(0)
            when (val decision = decidePlanWrite(stored, next, clock.millis())) {
                is PlanWriteDecision.Advance -> {
                    revisions += next
                    com.driezy.medlog.data.model.PlanWriteResult.Advanced
                }
                is PlanWriteDecision.Unchanged -> com.driezy.medlog.data.model.PlanWriteResult.NoOp
            }
        }

        // 只有备注变化：计划字段（含 offsetMinutes = 0）逐字相同。
        repository().updateMedication(stored.copy(notes = "只改备注"))

        verify(dao).updatePlan(any(), any())
        assertEquals("未变的保存必须零修订", 0, revisions.size)
    }

    @Test
    fun `materializedFor alone is not the gate but gatedFor is`() = runTest {
        val exact = medication(timePeriod = "", times = "09:00", offset = 15)

        // B1 事实：materializedFor 在 requireAllowed 之前早退，精确时间行会原样穿过。
        assertEquals(exact, exact.materializedFor(routine))
        // 落库路径必须调 gatedFor，所以同一个对象在 repository 里被拒。
        assertTrue(runCatching { repository().updateMedication(exact) }.exceptionOrNull() is IllegalTimeOffsetException)
        assertTrue(runCatching { exact.gatedFor(routine) }.exceptionOrNull() is IllegalTimeOffsetException)
    }

    private fun medication(
        timePeriod: String,
        times: String,
        offset: Int,
        isPRN: Boolean = false,
        effectiveFrom: Long = 0L,
    ) = Medication(
        id = 11L,
        name = "药品",
        doseUnit = "片",
        isPRN = isPRN,
        timePeriod = timePeriod,
        reminderTimes = times,
        reminderHour = times.substringBefore(':').toInt(),
        reminderMinute = times.substringAfter(':').toInt(),
        offsetMinutes = offset,
        careRecipientId = RECIPIENT,
        planEffectiveFromMs = effectiveFrom,
    )

    private companion object {
        const val RECIPIENT = 7L
    }
}
