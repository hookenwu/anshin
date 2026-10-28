package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 时间解析的**单一权威**（docs/medication-time-offset.md §2.3 / §5.0 / §8 第 1 条）。
 *
 * 钉住四件事：
 * 1. `resolve` 与旧的 `LocalTime.plus/minusMinutes` 实现**逐字等价**（0 偏移的祖父条款）；
 * 2. `resolveUnwrapped` 是唯一保留"未回绕"语义的入口（可 `< 0` / `≥ 1440`）；
 * 3. `resolve = floorMod(resolveUnwrapped, 1440)`；
 * 4. `resolveWithOffset` 是唯一把偏移与基线相加的地方，越界**抛异常**而不是钳位。
 */
class RoutineTimeResolutionTest {

    private val defaultSchedule = RoutineSchedule()

    private val schedules = listOf(
        defaultSchedule,
        // 早餐 00:10 / 就寝 00:30：两条回绕基线（`BEFORE_BREAKFAST = -5`、`EVENING = -30`）
        RoutineSchedule(breakfast = RoutineTime(0, 10), bed = RoutineTime(0, 30)),
        RoutineSchedule(
            wake = RoutineTime(7, 45),
            breakfast = RoutineTime(8, 0),
            lunch = RoutineTime(12, 10),
            dinner = RoutineTime(18, 5),
            bed = RoutineTime(23, 30),
        ),
    )

    @Test
    fun `resolve is byte-identical to the legacy LocalTime arithmetic for every anchor`() {
        for (schedule in schedules) {
            for (anchor in RoutineAnchor.entries) {
                assertEquals("$anchor / $schedule", legacyResolve(schedule, anchor), schedule.resolve(anchor))
            }
        }
    }

    @Test
    fun `resolve is floorMod of the unwrapped minute of day for every anchor`() {
        for (schedule in schedules) {
            for (anchor in RoutineAnchor.entries) {
                val expected = minuteOfDayToLocalTime(Math.floorMod(schedule.resolveUnwrapped(anchor), MINUTES_PER_DAY))
                assertEquals("$anchor / $schedule", expected, schedule.resolve(anchor))
            }
        }
    }

    @Test
    fun `wrapped breakfast keeps the legacy rendering but exposes a negative unwrapped baseline`() {
        val schedule = defaultSchedule.copy(breakfast = RoutineTime(0, 10))
        assertEquals(-5, schedule.resolveUnwrapped(RoutineAnchor.BEFORE_BREAKFAST))
        assertEquals(LocalTime.of(23, 55), schedule.resolve(RoutineAnchor.BEFORE_BREAKFAST))
        assertEquals(25, schedule.resolveUnwrapped(RoutineAnchor.AFTER_BREAKFAST))
        assertEquals(LocalTime.of(0, 25), schedule.resolve(RoutineAnchor.AFTER_BREAKFAST))
    }

    @Test
    fun `wrapped bed keeps the legacy rendering but exposes a negative evening baseline`() {
        val schedule = defaultSchedule.copy(bed = RoutineTime(0, 30))
        assertEquals(-30, schedule.resolveUnwrapped(RoutineAnchor.EVENING))
        assertEquals(LocalTime.of(23, 30), schedule.resolve(RoutineAnchor.EVENING))
        assertEquals(30, schedule.resolveUnwrapped(RoutineAnchor.BEDTIME))
    }

    @Test
    fun `zero offset is the grandfather clause and equals resolve for every anchor`() {
        for (schedule in schedules) {
            for (anchor in RoutineAnchor.entries) {
                assertEquals("$anchor / $schedule", schedule.resolve(anchor), schedule.resolveWithOffset(anchor, 0))
            }
        }
    }

    @Test
    fun `every legal offset moves the rendered time and no legal offset collapses to the baseline`() {
        // §8 第 1 条：只对"校验会放行"的 (anchor, offset) 断言；回绕锚点的非 0 档在校验层就被拒，
        // 到不了物化。这里逐个走 validateTimeOffset，与生产路径同一判据。
        for (schedule in schedules) {
            for (anchor in RoutineAnchor.entries) {
                val baseline = schedule.resolve(anchor)
                for (offset in -60..60 step 15) {
                    val legal = validateTimeOffset(offset, schedule, listOf(anchor), applicable = true)
                    if (legal !is TimeOffsetCheck.Allowed) continue
                    val resolved = schedule.resolveWithOffset(anchor, offset)
                    if (offset == 0) {
                        assertEquals(baseline, resolved)
                    } else {
                        assertNotEquals("$anchor $offset 必须真的改变钟点", baseline, resolved)
                        assertEquals(
                            schedule.resolveUnwrapped(anchor) + offset,
                            resolved.hour * 60 + resolved.minute,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `offset that would cross midnight throws instead of wrapping`() {
        val schedule = defaultSchedule.copy(bed = RoutineTime(23, 30))
        // 1410 + 60 = 1470 越界：不取模、不钳位到 23:45
        val failure = runCatching { schedule.resolveWithOffset(RoutineAnchor.BEDTIME, 60) }
        assertTrue("必须先抛 IllegalTimeOffsetException，而不是回绕成 00:30", failure.isFailure)
        val check = (failure.exceptionOrNull() as IllegalTimeOffsetException).check
        assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, check.reason)
        assertEquals(RoutineAnchor.BEDTIME, check.anchor)
        assertEquals(1470, check.resolvedMinuteOfDay)
        // +15 是这一档的合法上限
        assertEquals(LocalTime.of(23, 45), schedule.resolveWithOffset(RoutineAnchor.BEDTIME, 15))
    }

    @Test
    fun `minute of day conversion is the only LocalTime construction point`() {
        assertEquals(LocalTime.of(0, 0), minuteOfDayToLocalTime(0))
        assertEquals(LocalTime.of(23, 59), minuteOfDayToLocalTime(1439))
        assertEquals(1439, MINUTES_PER_DAY - 1)
    }

    /** 迁移前的实现：`LocalTime.plus/minusMinutes/minusHours`。语义必须与 [RoutineSchedule.resolve] 相同。 */
    private fun legacyResolve(schedule: RoutineSchedule, anchor: RoutineAnchor): LocalTime = when (anchor) {
        RoutineAnchor.MORNING -> schedule.wake.toLocalTime()
        RoutineAnchor.BEFORE_BREAKFAST -> schedule.breakfast.toLocalTime().minusMinutes(15)
        RoutineAnchor.AFTER_BREAKFAST -> schedule.breakfast.toLocalTime().plusMinutes(15)
        RoutineAnchor.BEFORE_LUNCH -> schedule.lunch.toLocalTime().minusMinutes(15)
        RoutineAnchor.AFTER_LUNCH -> schedule.lunch.toLocalTime().plusMinutes(15)
        RoutineAnchor.BEFORE_DINNER -> schedule.dinner.toLocalTime().minusMinutes(15)
        RoutineAnchor.AFTER_DINNER -> schedule.dinner.toLocalTime().plusMinutes(15)
        RoutineAnchor.AFTERNOON -> LocalTime.of(15, 0)
        RoutineAnchor.EVENING -> schedule.bed.toLocalTime().minusHours(1)
        RoutineAnchor.BEDTIME -> schedule.bed.toLocalTime()
    }
}
