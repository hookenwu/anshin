package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 写路径唯一物化点 `Medication.materializedFor`（docs/medication-time-offset.md §5.0.1、§8 第 1/2/3 条）。
 *
 * 钉住：0 偏移逐字等于旧解析（E6）、偏移确实位移、多时段升序去重、非适格原样返回、
 * 非法偏移**抛异常且不写任何值**（绝不钳位）、同一输入连算两次结果相同（幂等）。
 */
class MedicationMaterializationTest {

    private val schedule = RoutineSchedule()

    @Test
    fun `zero offset reproduces the legacy resolution for every period`() {
        for (period in TimePeriod.entries.filter { it != TimePeriod.EXACT }) {
            val anchor = period.toAnchorOrNull()!!
            val expected = schedule.resolve(anchor)
            val materialized = medication(listOf(period)).materializedFor(schedule)
            assertEquals("$period", format(expected), materialized.reminderTimes)
            assertEquals(expected.hour, materialized.reminderHour)
            assertEquals(expected.minute, materialized.reminderMinute)
        }
    }

    @Test
    fun `offset shifts every reminder time and is written back into the legacy columns`() {
        val materialized = medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 15).materializedFor(schedule)
        // breakfast 08:00 + 15(anchor) + 15(offset) = 08:30
        assertEquals("08:30", materialized.reminderTimes)
        assertEquals(8, materialized.reminderHour)
        assertEquals(30, materialized.reminderMinute)
        assertEquals(15, materialized.offsetMinutes)
    }

    @Test
    fun `multiple periods are shifted together and kept distinct and sorted`() {
        val materialized = medication(
            listOf(TimePeriod.BEFORE_BREAKFAST, TimePeriod.AFTER_DINNER),
            offset = 30,
        ).materializedFor(schedule)
        // 07:45 + 30 = 08:15、18:15 + 30 = 18:45
        assertEquals("08:15,18:45", materialized.reminderTimes)
        assertEquals(8, materialized.reminderHour)
        assertEquals(15, materialized.reminderMinute)
    }

    @Test
    fun `periods that already collide stay a single slot at every legal offset`() {
        // wake = 07:45 ⇒ MORNING 与 BEFORE_BREAKFAST 本已撞点（E3）
        val colliding = schedule.copy(wake = RoutineTime(7, 45))
        for (offset in -60..60 step 15) {
            val materialized = medication(
                listOf(TimePeriod.MORNING, TimePeriod.BEFORE_BREAKFAST),
                offset = offset,
            ).materializedFor(colliding)
            assertEquals("offset=$offset", 1, materialized.reminderTimes.split(",").size)
        }
    }

    @Test
    fun `non applicable medications are returned untouched`() {
        val exactRow = medication(emptyList())
        val exact = exactRow.materializedFor(schedule)
        assertEquals("精确时间没有基线 ⇒ 原样返回", exactRow, exact)

        val prn = medication(listOf(TimePeriod.AFTER_BREAKFAST), isPrn = true).materializedFor(schedule)
        assertEquals("08:00", prn.reminderTimes)

        val interval = medication(listOf(TimePeriod.AFTER_BREAKFAST), intervalHours = 8).materializedFor(schedule)
        assertEquals("08:00", interval.reminderTimes)

        // materializedFor 对非适格/无基线行**原样返回**（§5.0.1 的早退），拒绝发生在写入口：
        // 任何写点都必须先调 requireAllowed(validateTimeOffset(...))，那里会判 NOT_APPLICABLE。
        val exactWithOffset = medication(emptyList(), offset = 15)
        assertEquals(exactWithOffset, exactWithOffset.materializedFor(schedule))
        assertEquals(
            TimeOffsetCheck.Reason.NOT_APPLICABLE,
            (
                validateTimeOffset(
                    15,
                    schedule,
                    TimePeriods.parse(exactWithOffset.timePeriod).mapNotNull { it.toAnchorOrNull() },
                    applicable = true,
                ) as TimeOffsetCheck.Rejected
                ).reason,
        )
    }

    @Test
    fun `materialization is idempotent`() {
        val once = medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = -30).materializedFor(schedule)
        val twice = once.materializedFor(schedule)
        assertEquals(once.reminderTimes, twice.reminderTimes)
        assertEquals(once.reminderHour, twice.reminderHour)
        assertEquals(once.reminderMinute, twice.reminderMinute)
    }

    @Test
    fun `crossing midnight is rejected and never clamped`() {
        val lateBed = schedule.copy(bed = RoutineTime(23, 30))
        val failure = runCatching {
            medication(listOf(TimePeriod.BEDTIME), offset = 60).materializedFor(lateBed)
        }
        assertTrue("必须拒绝而不是钳到 +15", failure.isFailure)
        val check = (failure.exceptionOrNull() as IllegalTimeOffsetException).check
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, check.reason)
        assertEquals(LocalTime.of(23, 30), check.base)
    }

    @Test
    fun `wrapped anchor is rejected for any non zero offset`() {
        val wrapped = schedule.copy(breakfast = RoutineTime(0, 10))
        for (offset in listOf(15, -15)) {
            val failure = runCatching {
                medication(listOf(TimePeriod.BEFORE_BREAKFAST), offset = offset).materializedFor(wrapped)
            }
            assertTrue("$offset 必须被拒绝", failure.isFailure)
            assertEquals(
                TimeOffsetCheck.Reason.WRAPPED_ANCHOR,
                (failure.exceptionOrNull() as IllegalTimeOffsetException).check.reason,
            )
        }
        // 0 偏移仍然合法，且渲染逐字等于旧值（祖父条款）
        assertEquals(
            "23:55",
            medication(listOf(TimePeriod.BEFORE_BREAKFAST)).materializedFor(wrapped).reminderTimes,
        )
    }

    @Test
    fun `every period resolves through the single authority`() {
        // 物化结果必须等于该锚点在本作息下的 resolve（多时段时逐条对应），防止第二套算术
        for (period in TimePeriod.entries.filter { it != TimePeriod.EXACT }) {
            val anchor: RoutineAnchor = period.toAnchorOrNull()!!
            val materialized = medication(listOf(period)).materializedFor(schedule)
            assertEquals(period.name, format(schedule.resolve(anchor)), materialized.reminderTimes)
        }
    }

    private fun medication(
        periods: List<TimePeriod>,
        offset: Int = 0,
        isPrn: Boolean = false,
        intervalHours: Int = 0,
    ) = Medication(
        name = "测试药",
        doseUnit = "片",
        timePeriod = TimePeriods.encode(periods),
        reminderTimes = "08:00",
        reminderHour = 8,
        reminderMinute = 0,
        offsetMinutes = offset,
        isPRN = isPrn,
        intervalHours = intervalHours,
    )

    private fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
}
