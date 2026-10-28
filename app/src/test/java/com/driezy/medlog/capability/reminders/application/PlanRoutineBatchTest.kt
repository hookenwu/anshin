package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.data.model.TimePeriods
import com.driezy.medlog.data.model.resolve
import com.driezy.medlog.domain.model.RoutineAnchor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 作息重算的核心断言（docs/medication-time-offset.md §3.5 / §5.0.1，PLAN §4.2/§4.3 R1-R7）：
 * 走**同一个**物化点、比较的是**物化结果**而不是基线、任何拒绝整批回滚且零部分写入。
 *
 * 「零部分写入」在这里落到「批处理只会整批成功或整批抛出，抛出时调用方不可能提交」；
 * Room 真实事务的回滚由 `core/database` 的 `MedicationDaoTransactionTest` 钉住，
 * 「一次事务、一个 changedAt」由 `PlanForRoutineTransactionTest` 钉住。
 */
class PlanRoutineBatchTest {

    // ── 1. 偏移药在新作息下重算到偏移后的时刻，偏移列原样保留 ────────────────────────────────

    @Test
    fun `offset bearing medication is refreshed to the shifted time and keeps its offset`() = runTest {
        val shifted = medication(
            id = 1L,
            timePeriod = "afterBreakfast",
            reminderTimes = "08:15",
            offsetMinutes = 15,
        )
        val tx = BatchTransaction()

        runBatch(tx, listOf(shifted), schedule(breakfast = RoutineTime(9, 0)))

        val written = tx.committed.single()
        // afterBreakfast = breakfast + 15 分钟；库里 08:15 是"07:45 基线 + 15 偏移"的结果。
        // 新作息 09:00 ⇒ 基线 09:15、加 15 偏移 ⇒ 09:30。旧实现会写成基线 09:15 并把偏移丢掉。
        assertEquals("09:30", written.reminderTimes)
        assertEquals(9, written.reminderHour)
        assertEquals(30, written.reminderMinute)
        assertEquals("偏移列必须原样保留（不是被重算，也不是被归零）", 15, written.offsetMinutes)
    }

    // ── 2. 只有偏移在变也必须被发现（旧代码拿基线比库里的钟点 ⇒ 永远"未变"） ────────────────────

    @Test
    fun `a medication whose only change is the offset is detected`() = runTest {
        // 库里：breakfast 07:45 + 15 分钟偏移 ⇒ 存的是 08:15。新作息 breakfast 08:00 ⇒
        // 基线 08:15（恰好等于库里的钟点）、正确结果 08:30 ⇒ 旧实现拿基线比库里的值，判成"未变"并跳过。
        val offsetOnly = medication(
            id = 2L,
            timePeriod = "afterBreakfast",
            reminderTimes = "08:15",
            offsetMinutes = 15,
        )
        val newSchedule = schedule(breakfast = RoutineTime(8, 0))
        assertEquals(
            "前提：基线确实等于库里的钟点",
            LocalTime.of(8, 15),
            newSchedule.resolve(RoutineAnchor.AFTER_BREAKFAST),
        )

        val tx = BatchTransaction()
        val result = runBatch(tx, listOf(offsetOnly), newSchedule)

        assertEquals("这一味必须被写", 1, result.advanced)
        assertEquals("08:30", tx.committed.single().reminderTimes)
    }

    // ── 3. 真的没变 ⇒ 零写 ────────────────────────────────────────────────────────────────────

    @Test
    fun `unchanged materialized times are not written`() = runTest {
        val unchanged = medication(id = 3L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        val tx = BatchTransaction()

        val result = runBatch(tx, listOf(unchanged), RoutineSchedule())

        assertTrue("不得调用写路径：${tx.attempted}", tx.attempted.isEmpty())
        assertTrue("不得落地任何行", tx.committed.isEmpty())
        assertEquals(0, result.advanced)
        assertEquals(1, result.noOp)
    }

    // ── 4. 拒绝 ⇒ 整批回滚、零部分写入、冲突具名 ──────────────────────────────────────────────

    @Test
    fun `a rejection rolls back the whole batch with zero partial writes`() = runTest {
        val legal = medication(id = 10L, timePeriod = "afterBreakfast", reminderTimes = "08:15", offsetMinutes = 15)
        val illegal = medication(id = 11L, timePeriod = "bedtime", reminderTimes = "22:30", offsetMinutes = 30)
        // bed 22:00 +30 = 22:30 合法；bed 23:30 +30 = 00:00 跨零点 ⇒ 不可物化
        val newSchedule = schedule(bed = RoutineTime(23, 30))
        val tx = BatchTransaction()

        val error = runCatching {
            planRoutineBatch(listOf(legal, illegal), newSchedule, CHANGED_AT, write = tx::write)
        }.exceptionOrNull()

        assertTrue("必须是具名冲突异常，实际：$error", error is RoutineResyncConflictException)
        val conflicts = (error as RoutineResyncConflictException).conflicts
        assertEquals("只报被拒的那一味", listOf(11L), conflicts.map(OffsetConflict::medicationId))
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, conflicts.single().reason)
        assertEquals("冲突行必须带药名，对话框要指名", "Medication 11", conflicts.single().name)

        // 收集而非早退：合法药确实走到过写路径……
        assertEquals("合法药必须已经尝试写过（收集后抛出，不是首拒即停）", listOf(10L), tx.attempted)
        // ……但整批抛出 ⇒ 没有 commit ⇒ 一行都不落地。
        assertTrue("整批回滚：不得有任何部分写入，实际落地 ${tx.committed}", tx.committed.isEmpty())
    }

    // ── 5. 精确时间 / PRN / 间隔给药不在范围内（与改造前逐字一致） ─────────────────────────────

    @Test
    fun `exact and as-needed plans are not rewritten`() = runTest {
        val tx = BatchTransaction()
        val snapshot = listOf(
            medication(id = 30L, timePeriod = "exact", reminderTimes = "07:00"),
            medication(id = 31L, timePeriod = "beforeBreakfast", reminderTimes = "07:45", isPrn = true),
            medication(id = 32L, timePeriod = "beforeLunch", reminderTimes = "11:45", intervalHours = 6),
        )

        val result = runBatch(tx, snapshot, schedule(breakfast = RoutineTime(9, 0)))

        assertTrue("非适格行不得进入写路径", tx.attempted.isEmpty())
        assertEquals(PlanRoutineResult(advanced = 0, noOp = 0), result)
    }

    // ── 6. 每味"确实变了"的药恰一次写（= 恰一条修订），没变则零次 ─────────────────────────────

    @Test
    fun `one write per changed medication and none when unchanged`() = runTest {
        val changed = medication(id = 40L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        val alsoChanged = medication(id = 41L, timePeriod = "bedtime", reminderTimes = "22:00")
        val unchanged = medication(id = 42L, timePeriod = "afterDinner", reminderTimes = "18:15")
        val tx = BatchTransaction()

        val result = runBatch(
            tx,
            listOf(changed, alsoChanged, unchanged),
            schedule(breakfast = RoutineTime(9, 0), bed = RoutineTime(23, 0)),
        )

        assertEquals("每味变了的药恰好一次写（真实 DAO 里 = 恰一条修订）", listOf(40L, 41L), tx.attempted)
        assertEquals(2, tx.committed.size)
        assertEquals(2, result.advanced)
        assertEquals("没变的那一味计入 noOp", 1, result.noOp)
    }

    // ── 7. 非偏移类异常原样穿出（不吞、不包装） ──────────────────────────────────────────────

    @Test
    fun `plan boundary rejection propagates unwrapped`() = runTest {
        val med = medication(id = 50L, timePeriod = "afterBreakfast", reminderTimes = "08:15")
        val boundary = PlanBoundaryNotAdvancedException(
            medicationId = 50L,
            boundaryMs = CHANGED_AT,
            requestedMs = CHANGED_AT,
        )

        val error = runCatching {
            planRoutineBatch(listOf(med), schedule(breakfast = RoutineTime(9, 0)), CHANGED_AT) { throw boundary }
        }.exceptionOrNull()

        assertTrue("边界拒绝必须原样穿出（整批回滚），实际：$error", error === boundary)
    }

    // ── 8. 偏移 0 与改造前的算法逐字相同 ─────────────────────────────────────────────────────

    @Test
    fun `zero offset stays byte identical to the legacy batch`() = runTest {
        val newSchedule = RoutineSchedule(
            wake = RoutineTime(6, 30),
            breakfast = RoutineTime(8, 40),
            lunch = RoutineTime(12, 20),
            dinner = RoutineTime(19, 10),
            bed = RoutineTime(23, 20),
        )
        val snapshot = listOf(
            medication(id = 60L, timePeriod = "beforeBreakfast", reminderTimes = "01:00"),
            medication(id = 61L, timePeriod = "afterBreakfast,afterDinner", reminderTimes = "01:00"),
            medication(id = 62L, timePeriod = "evening", reminderTimes = "01:00"),
            medication(id = 63L, timePeriod = "morning", reminderTimes = "08:40"),
            medication(id = 64L, timePeriod = "exact", reminderTimes = "06:00"),
        )

        val tx = BatchTransaction()
        runBatch(tx, snapshot, newSchedule)

        val legacy = snapshot
            .map { med -> med to legacyMaterialize(med, newSchedule) }
            // 旧实现先跳过非适格行（PRN / 间隔给药 / 无锚点），再比钟点
            .filter { (med, times) -> times.isNotEmpty() && !med.isPRN && med.intervalHours <= 0 }
            .filter { (med, times) -> times != med.storedTimes() }
            .map { (med, times) -> med.withLegacyTimes(times) }

        assertEquals("写入集合必须与旧算法逐条相同", legacy.map(Medication::id), tx.committed.map(Medication::id))
        assertEquals("写入内容必须与旧算法逐字相同", legacy, tx.committed)
        assertTrue("偏移 0 的行不允许被改写成别的偏移", tx.committed.all { it.offsetMinutes == 0 })
    }

    // ── 9. 归零覆盖（「将偏移归零并继续」）在同一批内结算 ──────────────────────────────────────

    @Test
    fun `zero offset override settles in the same batch`() = runTest {
        val conflicting = medication(id = 70L, timePeriod = "bedtime", reminderTimes = "22:30", offsetMinutes = 30)
        val tx = BatchTransaction()

        val result = runBatch(
            tx,
            listOf(conflicting),
            schedule(bed = RoutineTime(23, 30)),
            zeroOffsetFor = setOf(70L),
        )

        val written = tx.committed.single()
        assertEquals("23:30", written.reminderTimes)
        assertEquals("覆盖值必须落库，否则下次重算又按旧偏移冲突", 0, written.offsetMinutes)
        assertEquals(1, result.advanced)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** 跑一批并在**正常返回**时提交；抛异常时调用方不会调用 [BatchTransaction.commit]。 */
    private suspend fun runBatch(
        tx: BatchTransaction,
        snapshot: List<Medication>,
        newSchedule: RoutineSchedule,
        zeroOffsetFor: Set<Long> = emptySet(),
    ): PlanRoutineResult {
        val result = planRoutineBatch(snapshot, newSchedule, CHANGED_AT, zeroOffsetFor, tx::write)
        tx.commit()
        return result
    }

    /**
     * 极简事务：`write` 只进缓冲，[commit] 才落地；批处理抛异常 ⇒ 缓冲丢弃。
     * 因此 `committed == emptyList()` 就是"零部分写入"。
     */
    private class BatchTransaction {
        private val pending = mutableListOf<Medication>()
        private val applied = mutableListOf<Medication>()

        /** 走到过写路径的药（含回滚掉的那些：用来证明"收集而非早退"）。 */
        val attempted = mutableListOf<Long>()
        val committed: List<Medication> get() = applied

        suspend fun write(medication: Medication): PlanWriteResult {
            attempted += medication.id
            pending += medication
            return PlanWriteResult.Advanced
        }

        fun commit() {
            applied += pending
            pending.clear()
        }
    }

    private fun schedule(
        breakfast: RoutineTime = RoutineTime(8, 0),
        dinner: RoutineTime = RoutineTime(18, 0),
        bed: RoutineTime = RoutineTime(22, 0),
    ) = RoutineSchedule(breakfast = breakfast, dinner = dinner, bed = bed)

    private fun medication(
        id: Long,
        timePeriod: String,
        reminderTimes: String,
        offsetMinutes: Int = 0,
        isPrn: Boolean = false,
        intervalHours: Int = 0,
    ) = Medication(
        id = id,
        name = "Medication $id",
        doseUnit = "tablet",
        timePeriod = timePeriod,
        reminderTimes = reminderTimes,
        reminderHour = reminderTimes.substringBefore(':').toInt(),
        reminderMinute = reminderTimes.substringAfter(':').toInt(),
        offsetMinutes = offsetMinutes,
        isPRN = isPrn,
        intervalHours = intervalHours,
    )

    /** **改造前**的算法，原样抄一份当 oracle：基线、升序去重、无偏移项。 */
    private fun legacyMaterialize(med: Medication, newSchedule: RoutineSchedule): List<LocalTime> =
        TimePeriods.parse(med.timePeriod)
            .mapNotNull { period -> runCatching { RoutineAnchor.valueOf(period.name) }.getOrNull() }
            .map(newSchedule::resolve)
            .distinct()
            .sorted()

    private fun Medication.storedTimes(): List<LocalTime> = reminderTimes.split(',')
        .mapNotNull { raw -> runCatching { LocalTime.parse(raw.trim()) }.getOrNull() }
        .distinct()
        .sorted()

    private fun Medication.withLegacyTimes(times: List<LocalTime>): Medication {
        val sorted = times.distinct().sorted()
        return copy(
            reminderTimes = sorted.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) },
            reminderHour = sorted.first().hour,
            reminderMinute = sorted.first().minute,
        )
    }

    private companion object {
        const val CHANGED_AT = 1_700_000_000_000L
    }
}
