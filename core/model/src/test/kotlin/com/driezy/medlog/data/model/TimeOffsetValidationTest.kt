package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 偏移合法性判据（docs/medication-time-offset.md §2.2 / §2.3，边界 E1 / E7 / E10）。
 *
 * 只钉两件事：**判据**（解析后的瞬时 + 回绕基线）与**拒绝**（已定义异常、绝不钳位）。
 */
class TimeOffsetValidationTest {

    private val defaultSchedule = RoutineSchedule()

    @Test
    fun `zero offset is always allowed even on a wrapped baseline`() {
        val wrapped = defaultSchedule.copy(breakfast = RoutineTime(0, 10))
        assertEquals(
            TimeOffsetCheck.Allowed(0),
            validateTimeOffset(0, wrapped, listOf(RoutineAnchor.BEFORE_BREAKFAST), applicable = true),
        )
        // 没有基线、非适格也一样：0 是祖父条款
        assertEquals(TimeOffsetCheck.Allowed(0), validateTimeOffset(0, wrapped, emptyList(), applicable = false))
    }

    @Test
    fun `offsets that are not multiples of fifteen are rejected first`() {
        val check = validateTimeOffset(17, defaultSchedule, listOf(RoutineAnchor.AFTER_BREAKFAST), applicable = true)
        assertEquals(TimeOffsetCheck.Reason.NOT_MULTIPLE_OF_15, (check as TimeOffsetCheck.Rejected).reason)
        assertEquals(17, check.minutes)
    }

    @Test
    fun `offsets beyond sixty minutes are rejected`() {
        for (offset in listOf(75, 90, -75, -90)) {
            val check = validateTimeOffset(offset, defaultSchedule, listOf(RoutineAnchor.AFTER_BREAKFAST), true)
            assertEquals(
                "$offset 必须判 OUT_OF_RANGE",
                TimeOffsetCheck.Reason.OUT_OF_RANGE,
                (check as TimeOffsetCheck.Rejected).reason,
            )
        }
    }

    @Test
    fun `non applicable medication cannot carry a non zero offset`() {
        // 精确时间：没有可解析基线
        val exact = validateTimeOffset(15, defaultSchedule, emptyList(), applicable = true)
        assertEquals(TimeOffsetCheck.Reason.NOT_APPLICABLE, (exact as TimeOffsetCheck.Rejected).reason)
        assertEquals(null, exact.anchor)
        // PRN / 间隔给药：applicable = false
        val prn = validateTimeOffset(
            15,
            defaultSchedule,
            listOf(RoutineAnchor.AFTER_BREAKFAST),
            applicable = false,
        )
        assertEquals(TimeOffsetCheck.Reason.NOT_APPLICABLE, (prn as TimeOffsetCheck.Rejected).reason)
    }

    @Test
    fun `bedtime thirty minutes past the last legal slot crosses midnight`() {
        // bed = 23:30 ⇒ BEDTIME = 1410；+15 = 23:45 合法，+30/+45/+60 越界
        val schedule = defaultSchedule.copy(bed = RoutineTime(23, 30))
        assertEquals(
            TimeOffsetCheck.Allowed(15),
            validateTimeOffset(15, schedule, listOf(RoutineAnchor.BEDTIME), applicable = true),
        )
        for (offset in listOf(30, 45, 60)) {
            val check = validateTimeOffset(offset, schedule, listOf(RoutineAnchor.BEDTIME), applicable = true)
            val rejected = check as TimeOffsetCheck.Rejected
            assertEquals("$offset", TimeOffsetCheck.Reason.CROSS_MIDNIGHT, rejected.reason)
            assertEquals(RoutineAnchor.BEDTIME, rejected.anchor)
            assertEquals(LocalTime.of(23, 30), rejected.base)
            assertEquals(1410 + offset, rejected.resolvedMinuteOfDay)
        }
        // 同一味药的 EVENING（bed − 60 = 22:30）+60 = 23:30 仍合法 ⇒ 只看越界的那一段
        assertEquals(
            TimeOffsetCheck.Allowed(60),
            validateTimeOffset(60, schedule, listOf(RoutineAnchor.EVENING), applicable = true),
        )
    }

    @Test
    fun `multi period intersection rejects the whole slot and names the crossing anchor`() {
        val schedule = defaultSchedule.copy(bed = RoutineTime(23, 30))
        val check = validateTimeOffset(
            -60,
            schedule,
            listOf(RoutineAnchor.AFTER_BREAKFAST, RoutineAnchor.BEFORE_LUNCH),
            applicable = true,
        )
        // 08:15 - 60 = 07:15、11:45 - 60 = 10:45 ⇒ 都合法
        assertEquals(TimeOffsetCheck.Allowed(-60), check)

        val crossing = validateTimeOffset(
            60,
            schedule,
            listOf(RoutineAnchor.AFTER_BREAKFAST, RoutineAnchor.BEDTIME),
            applicable = true,
        ) as TimeOffsetCheck.Rejected
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, crossing.reason)
        assertEquals(RoutineAnchor.BEDTIME, crossing.anchor)
    }

    @Test
    fun `wrapped baseline rejects any non zero offset regardless of the numeric interval`() {
        // breakfast = 00:10 ⇒ BEFORE_BREAKFAST 基线 -5（渲染 23:55）
        val wrapped = defaultSchedule.copy(breakfast = RoutineTime(0, 10))
        for (offset in listOf(15, -15, 30, -30, 60, -60)) {
            val check = validateTimeOffset(offset, wrapped, listOf(RoutineAnchor.BEFORE_BREAKFAST), true)
            val rejected = check as TimeOffsetCheck.Rejected
            assertEquals("$offset 必须判 WRAPPED_ANCHOR", TimeOffsetCheck.Reason.WRAPPED_ANCHOR, rejected.reason)
            assertEquals(RoutineAnchor.BEFORE_BREAKFAST, rejected.anchor)
            assertEquals(-5, rejected.resolvedMinuteOfDay - offset)
        }
    }

    @Test
    fun `wrapped rejection only names the wrapped anchor and does not over reject`() {
        val wrapped = defaultSchedule.copy(breakfast = RoutineTime(0, 10))
        // AFTER_BREAKFAST 基线 25 未回绕 ⇒ 单时段 +15 = 00:40 合法（不得被连带拒绝）
        assertEquals(
            TimeOffsetCheck.Allowed(15),
            validateTimeOffset(15, wrapped, listOf(RoutineAnchor.AFTER_BREAKFAST), applicable = true),
        )
        // 同一味药同时带 BEFORE_BREAKFAST ⇒ 整药拒绝，且指名回绕的那一段
        val both = validateTimeOffset(
            15,
            wrapped,
            listOf(RoutineAnchor.AFTER_BREAKFAST, RoutineAnchor.BEFORE_BREAKFAST),
            applicable = true,
        ) as TimeOffsetCheck.Rejected
        assertEquals(TimeOffsetCheck.Reason.WRAPPED_ANCHOR, both.reason)
        assertEquals(RoutineAnchor.BEFORE_BREAKFAST, both.anchor)
    }

    @Test
    fun `wrapped evening and crossing bedtime are distinguishable reasons`() {
        // bed = 00:30 ⇒ EVENING 未回绕基线 -30（覆盖 minusHours 的回绕）
        val earlyBed = defaultSchedule.copy(bed = RoutineTime(0, 30))
        val evening = validateTimeOffset(15, earlyBed, listOf(RoutineAnchor.EVENING), true)
        assertEquals(TimeOffsetCheck.Reason.WRAPPED_ANCHOR, (evening as TimeOffsetCheck.Rejected).reason)
        assertEquals(-30, evening.resolvedMinuteOfDay - 15)
        // 同一作息下 BEDTIME 基线 30 未回绕 ⇒ 非 0 偏移照常按区间判定
        assertEquals(
            TimeOffsetCheck.Allowed(60),
            validateTimeOffset(60, earlyBed, listOf(RoutineAnchor.BEDTIME), applicable = true),
        )

        // bed = 23:50 ⇒ BEDTIME = 1430，+15 = 1445 越界 ⇒ 走 CROSS_MIDNIGHT（不是 WRAPPED_ANCHOR）
        val lateBed = defaultSchedule.copy(bed = RoutineTime(23, 50))
        val bedtime = validateTimeOffset(15, lateBed, listOf(RoutineAnchor.BEDTIME), true)
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, (bedtime as TimeOffsetCheck.Rejected).reason)
        assertEquals(1445, bedtime.resolvedMinuteOfDay)
    }

    @Test
    fun `requireAllowed throws the defined exception and returns the minute value when allowed`() {
        assertEquals(30, requireAllowed(TimeOffsetCheck.Allowed(30)))
        val failure = runCatching {
            requireAllowed(
                validateTimeOffset(
                    60,
                    defaultSchedule.copy(bed = RoutineTime(23, 30)),
                    listOf(RoutineAnchor.BEDTIME),
                    applicable = true,
                ),
            )
        }
        assertTrue(failure.isFailure)
        val exception = failure.exceptionOrNull() as IllegalTimeOffsetException
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, exception.check.reason)
        assertTrue(exception.message!!.contains("CROSS_MIDNIGHT"))
    }
}
