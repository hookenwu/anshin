package com.driezy.medlog.data.repository

import com.driezy.medlog.capability.reminders.application.RoutineResyncConflictException
import com.driezy.medlog.data.local.MedicationDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `MedicationRepository.planForRoutine` 的事务形状（docs/medication-time-offset.md §3.5，PLAN §4.2）：
 * **一次**事务、**一个** `changedAt` 全批共用、每味真的变了的药**恰一次** `updatePlan`
 * （⇒ 恰一条修订），任何拒绝让整批零写入。
 *
 * 这里用真实的 [MedicationRepositoryImpl] + 假的 [TransactionRunner] + mock 的 DAO：
 * `updatePlan` 只在事务块内被记录，事务抛异常 ⇒ 记录整个丢弃（模拟 Room 回滚）。
 */
class PlanForRoutineTransactionTest {

    @Test
    fun `one transaction and one changedAt for the whole batch`() = runTest {
        val dao: MedicationDao = mock()
        val tx = TransactionHarness()
        val clock = TickingClock()
        val breakfastMover = medication(id = 1L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        val dinnerMover = medication(id = 2L, timePeriod = "afterDinner", reminderTimes = "18:15")
        whenever(dao.getAllMedicationsOnce(RECIPIENT)).thenReturn(listOf(breakfastMover, dinnerMover))
        whenever(dao.updatePlan(any(), any())).thenAnswer { invocation ->
            tx.record(invocation.getArgument(0), invocation.getArgument(1))
            PlanWriteResult.Advanced
        }
        val repository = repository(dao, tx, clock)

        val result = repository.planForRoutine(
            RoutineSchedule(breakfast = RoutineTime(9, 0), dinner = RoutineTime(19, 0)),
        )

        assertEquals("整个批次只能开一次事务", 1, tx.transactions)
        assertEquals(2, result.advanced)
        assertEquals("每味变了的药恰一次 updatePlan（DAO 内 = 恰一条修订）", listOf(1L, 2L), tx.committed.map { it.first.id })
        assertEquals(
            "全批必须共用一个 changedAt（逐药取时钟会产生亚毫秒碎片区间）",
            1,
            tx.committed.map { it.second }.distinct().size,
        )
        assertEquals("边界必须是这次事务里取的那一个", clock.firstMillis, tx.committed.first().second)
    }

    @Test
    fun `nothing changed means no writes and no revisions`() = runTest {
        val dao: MedicationDao = mock()
        val tx = TransactionHarness()
        val unchanged = medication(id = 3L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        whenever(dao.getAllMedicationsOnce(RECIPIENT)).thenReturn(listOf(unchanged))
        val repository = repository(dao, tx)

        val result = repository.planForRoutine(RoutineSchedule())

        verify(dao, never()).updatePlan(any(), any())
        assertTrue(tx.committed.isEmpty())
        assertEquals(0, result.advanced)
        assertEquals(1, result.noOp)
    }

    @Test
    fun `an offset conflict rolls the whole batch back with zero writes`() = runTest {
        val dao: MedicationDao = mock()
        val tx = TransactionHarness()
        val legal = medication(id = 4L, timePeriod = "afterBreakfast", reminderTimes = "08:15", offsetMinutes = 15)
        val illegal = medication(id = 5L, timePeriod = "bedtime", reminderTimes = "22:30", offsetMinutes = 30)
        whenever(dao.getAllMedicationsOnce(RECIPIENT)).thenReturn(listOf(legal, illegal))
        whenever(dao.updatePlan(any(), any())).thenAnswer { invocation ->
            tx.record(invocation.getArgument(0), invocation.getArgument(1))
            PlanWriteResult.Advanced
        }
        val repository = repository(dao, tx)

        val error = runCatching {
            repository.planForRoutine(RoutineSchedule(bed = RoutineTime(23, 30)))
        }.exceptionOrNull()

        assertTrue("必须是具名冲突，实际：$error", error is RoutineResyncConflictException)
        assertEquals(
            "冲突必须具名到那一味药",
            listOf(5L),
            (error as RoutineResyncConflictException).conflicts.map { it.medicationId },
        )
        assertEquals("合法药的写入必须已经发生（收集后抛出）", listOf(4L), tx.attempted)
        assertTrue("整批回滚：一行都不得落地", tx.committed.isEmpty())
        assertEquals(1, tx.transactions)
    }

    @Test
    fun `a plan boundary rejection propagates and rolls the batch back`() = runTest {
        val dao: MedicationDao = mock()
        val tx = TransactionHarness()
        val first = medication(id = 6L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        val second = medication(id = 7L, timePeriod = "afterDinner", reminderTimes = "18:15")
        whenever(dao.getAllMedicationsOnce(RECIPIENT)).thenReturn(listOf(first, second))
        whenever(dao.updatePlan(any(), any())).thenAnswer { invocation ->
            val med: Medication = invocation.getArgument(0)
            if (med.id == 7L) {
                throw PlanBoundaryNotAdvancedException(
                    medicationId = med.id,
                    boundaryMs = TickingClock.START,
                    requestedMs = TickingClock.START,
                )
            }
            tx.record(med, invocation.getArgument(1))
            PlanWriteResult.Advanced
        }
        val repository = repository(dao, tx)

        val error = runCatching {
            repository.planForRoutine(RoutineSchedule(breakfast = RoutineTime(9, 0), dinner = RoutineTime(19, 0)))
        }.exceptionOrNull()

        assertTrue("边界拒绝必须原样穿出（不吞、不包装），实际：$error", error is PlanBoundaryNotAdvancedException)
        assertEquals("第一味确实写过", listOf(6L), tx.attempted)
        assertTrue("边界拒绝同样让整批回滚，零部分写入", tx.committed.isEmpty())
    }

    @Test
    fun `no recipient means no read and no write`() = runTest {
        val dao: MedicationDao = mock()
        val tx = TransactionHarness()
        val repository = repository(dao, tx, recipientId = ActiveRecipientStore.NO_RECIPIENT)

        val result = repository.planForRoutine(RoutineSchedule())

        verify(dao, never()).getAllMedicationsOnce(any())
        verify(dao, never()).updatePlan(any(), any())
        assertEquals(0, result.advanced)
    }

    private suspend fun repository(
        dao: MedicationDao,
        tx: TransactionHarness,
        clock: Clock = TickingClock(),
        recipientId: Long = RECIPIENT,
    ): MedicationRepositoryImpl {
        val activeRecipient: ActiveRecipientStore = mock()
        whenever(activeRecipient.current()).thenReturn(recipientId)
        return MedicationRepositoryImpl(dao, tx, activeRecipient, clock, mock())
    }

    private fun medication(id: Long, timePeriod: String, reminderTimes: String, offsetMinutes: Int = 0) = Medication(
        id = id,
        name = "Medication $id",
        doseUnit = "tablet",
        timePeriod = timePeriod,
        reminderTimes = reminderTimes,
        reminderHour = reminderTimes.substringBefore(':').toInt(),
        reminderMinute = reminderTimes.substringAfter(':').toInt(),
        offsetMinutes = offsetMinutes,
    )

    /** 极简事务：块内的 `updatePlan` 先进 `pending`，正常返回才进 `committed`，抛异常则整批丢弃。 */
    private class TransactionHarness : TransactionRunner {
        val pending = mutableListOf<Pair<Medication, Long>>()
        val committed = mutableListOf<Pair<Medication, Long>>()

        /** 走到过 `updatePlan` 的药（含随后被回滚掉的：用来证明"收集而非早退"）。 */
        val attempted = mutableListOf<Long>()
        var transactions = 0

        fun record(medication: Medication, changedAt: Long) {
            attempted += medication.id
            pending += medication to changedAt
        }

        override suspend fun <R> withTransaction(block: suspend () -> R): R {
            transactions++
            return try {
                val result = block()
                committed += pending
                pending.clear()
                result
            } catch (error: Throwable) {
                pending.clear()
                throw error
            }
        }
    }

    /** 每次 `millis()` 都递增：逐药取时钟会让 N 味药拿到 N 个不同值，从而被断言抓住。 */
    private class TickingClock : Clock() {
        var firstMillis: Long = START
            private set
        private var calls = 0

        override fun millis(): Long = (START + calls++).also { if (calls == 1) firstMillis = it }

        override fun instant(): Instant = Instant.ofEpochMilli(millis())

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        companion object {
            const val START = 1_700_000_000_000L
        }
    }

    private companion object {
        const val RECIPIENT = 7L
    }
}
