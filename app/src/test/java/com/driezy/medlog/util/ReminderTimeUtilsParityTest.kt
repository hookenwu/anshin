package com.driezy.medlog.util

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.resolve
import com.driezy.medlog.data.model.toAnchorOrNull
import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 双解析 parity（docs/medication-time-offset.md §5.0、§8 第 12 条）。
 *
 * `ReminderTimeUtils.timePeriodToReminderTime` 必须与权威 `RoutineSchedule.resolve` **逐字相同**
 * （含回绕边界），且 `TimePeriod` → `RoutineAnchor` 是**同名 1:1**——两套解析从此不可能各说各话。
 */
class ReminderTimeUtilsParityTest {

    private val schedules = listOf(
        RoutineSchedule(),
        // bed = 00:30 ⇒ EVENING 回绕到 23:30
        RoutineSchedule(bed = RoutineTime(0, 30)),
        // breakfast = 00:10 ⇒ BEFORE_BREAKFAST 回绕到 23:55
        RoutineSchedule(breakfast = RoutineTime(0, 10), bed = RoutineTime(23, 30)),
        RoutineSchedule(
            wake = RoutineTime(6, 30),
            breakfast = RoutineTime(9, 10),
            lunch = RoutineTime(13, 5),
            dinner = RoutineTime(19, 40),
            bed = RoutineTime(1, 15),
        ),
    )

    @Test
    fun `delegate equals the authority for every period and every schedule`() {
        for (schedule in schedules) {
            for (period in TimePeriod.entries.filter { it != TimePeriod.EXACT }) {
                val expected = format(schedule.resolve(period.toAnchorOrNull()!!))
                assertEquals(
                    "$period / $schedule",
                    expected,
                    ReminderTimeUtils.timePeriodToReminderTime(period, schedule),
                )
            }
        }
    }

    @Test
    fun `exact period has no anchor and yields an empty string`() {
        assertNull(TimePeriod.EXACT.toAnchorOrNull())
        assertEquals("", ReminderTimeUtils.timePeriodToReminderTime(TimePeriod.EXACT, schedules.first()))
    }

    @Test
    fun `time period and routine anchor map one to one by name`() {
        val periods = TimePeriod.entries.filter { it != TimePeriod.EXACT }
        assertEquals(
            "RoutineAnchor 与 TimePeriod 必须同名 1:1（新增枚举时必须同步映射）",
            RoutineAnchor.entries.map { it.name }.toSet(),
            periods.map { it.name }.toSet(),
        )
        for (period in periods) {
            assertNotNull("$period 必须能映射到锚点", period.toAnchorOrNull())
            assertEquals(period.name, period.toAnchorOrNull()!!.name)
        }
    }

    private fun format(time: java.time.LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
}
