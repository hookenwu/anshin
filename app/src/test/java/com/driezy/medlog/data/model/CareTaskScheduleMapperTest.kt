package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.MedicationSchedule
import com.driezy.medlog.domain.model.RoutineAnchor
import com.driezy.medlog.domain.model.ScheduleRecurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

/**
 * T4：CareTask → 排期领域模型的映射单测。
 *
 * 覆盖三个真实场景的排期形状（吸氧 固定时刻×3、翻身 完成后每 2 小时、读数 固定时段打卡），
 * 频率变体、混用（固定时段 + 间隔补做）与兜底行为。
 */
class CareTaskScheduleMapperTest {

    @Test
    fun `as needed task maps to AsNeeded`() {
        val task = task(scheduleKind = CareTaskScheduleKind.AS_NEEDED, reminderTimes = "20:00")

        assertEquals(MedicationSchedule.AsNeeded, task.toDomainSchedule())
    }

    @Test
    fun `oxygen therapy fixed times map to ExactTimes with three slots`() {
        // 吸氧：每天三次，每次 30 分钟（时长语义另外记录，排期只需三个钟点）
        val task = task(
            completionMode = CareTaskCompletionMode.DURATION,
            defaultDurationMinutes = 30,
            reminderTimes = "08:00,14:00,20:00",
        )

        val schedule = task.toDomainSchedule()
        assertTrue(schedule is MedicationSchedule.ExactTimes)
        assertEquals(
            listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)),
            (schedule as MedicationSchedule.ExactTimes).times,
        )
        assertEquals(ScheduleRecurrence.Daily, schedule.recurrence)
    }

    @Test
    fun `repositioning maps to completion anchored interval`() {
        // 翻身：每 2 小时一次 —— 下一次由"上次完成时间 + 间隔"推出，排期层无需新算法
        val task = task(
            scheduleKind = CareTaskScheduleKind.INTERVAL,
            intervalHours = 2,
        )

        assertEquals(MedicationSchedule.Interval(Duration.ofHours(2)), task.toDomainSchedule())
        assertEquals(Duration.ofHours(2), task.completionInterval())
    }

    @Test
    fun `interval without hours never yields a zero interval`() {
        val task = task(scheduleKind = CareTaskScheduleKind.INTERVAL, intervalHours = 0)

        assertEquals(MedicationSchedule.Interval(Duration.ofHours(1)), task.toDomainSchedule())
        assertNull("未配置间隔就不该有补做间隔", task.completionInterval())
    }

    @Test
    fun `single routine period maps to RoutineAnchored with resolved time`() {
        val task = task(timePeriods = "afterBreakfast", reminderTimes = "08:30")

        val schedule = task.toDomainSchedule()
        assertTrue(schedule is MedicationSchedule.RoutineAnchored)
        assertEquals(RoutineAnchor.AFTER_BREAKFAST, (schedule as MedicationSchedule.RoutineAnchored).anchor)
        assertEquals(LocalTime.of(8, 30), schedule.resolvedTime)
        assertEquals(ScheduleRecurrence.Daily, schedule.recurrence)
    }

    @Test
    fun `multiple routine periods expand to sorted exact times`() {
        // 同一个事项挂两个作息时段：编码期各换算一个钟点，排期展开成两个槽位
        val task = task(timePeriods = "afterBreakfast,afterDinner", reminderTimes = "18:30,08:30")

        val schedule = task.toDomainSchedule()
        assertTrue(schedule is MedicationSchedule.ExactTimes)
        assertEquals(
            listOf(LocalTime.of(8, 30), LocalTime.of(18, 30)),
            (schedule as MedicationSchedule.ExactTimes).times,
        )
    }

    @Test
    fun `duplicate and unparsable times are dropped and defaulted respectively`() {
        val duplicated = task(reminderTimes = "09:00,09:00,21:00")
        assertEquals(
            listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
            (duplicated.toDomainSchedule() as MedicationSchedule.ExactTimes).times,
        )

        val broken = task(reminderTimes = "")
        assertEquals(
            listOf(DEFAULT_REMINDER_TIME),
            (broken.toDomainSchedule() as MedicationSchedule.ExactTimes).times,
        )
    }

    @Test
    fun `interval frequency maps to EveryDays and specific days to Weekdays`() {
        val everyThreeDays = task(frequencyType = "interval", frequencyInterval = 3)
        assertEquals(
            ScheduleRecurrence.EveryDays(3),
            (everyThreeDays.toDomainSchedule() as MedicationSchedule.ExactTimes).recurrence,
        )

        // 大小写两种写法都接受（实体默认写 "DAILY"）
        val specificDays = task(frequencyType = "SPECIFIC_DAYS", frequencyDays = "1,3,5")
        val recurrence = (specificDays.toDomainSchedule() as MedicationSchedule.ExactTimes).recurrence
        assertTrue(recurrence is ScheduleRecurrence.Weekdays)
        assertEquals(3, (recurrence as ScheduleRecurrence.Weekdays).days.size)
    }

    @Test
    fun `empty specific days fall back to daily`() {
        val task = task(frequencyType = "specific_days", frequencyDays = "")

        assertEquals(
            ScheduleRecurrence.Daily,
            (task.toDomainSchedule() as MedicationSchedule.ExactTimes).recurrence,
        )
    }

    @Test
    fun `hybrid fixed periods plus interval keeps both a schedule and a top up interval`() {
        // 决策 3 的混用场景：有固定时段，同时需要间隔补做
        val task = task(timePeriods = "afterBreakfast", reminderTimes = "08:30", intervalHours = 3)

        val schedule = task.toDomainSchedule()
        assertTrue(schedule is MedicationSchedule.RoutineAnchored)
        assertEquals(Duration.ofHours(3), task.completionInterval())
    }

    @Test
    fun `as needed never reports a top up interval`() {
        val task = task(scheduleKind = CareTaskScheduleKind.AS_NEEDED, intervalHours = 4)

        assertNull(task.completionInterval())
    }

    @Test
    fun `earliest scheduled time follows the schedule shape`() {
        val multiple = task(reminderTimes = "20:00,08:00,12:00")
        assertEquals(LocalTime.of(8, 0), multiple.earliestScheduledTime())

        val anchored = task(timePeriods = "beforeLunch", reminderTimes = "11:30")
        assertEquals(LocalTime.of(11, 30), anchored.earliestScheduledTime())

        val interval = task(scheduleKind = CareTaskScheduleKind.INTERVAL, intervalHours = 2)
        assertEquals(DEFAULT_REMINDER_TIME, interval.earliestScheduledTime())
    }

    @Test
    fun `completion mode does not affect the schedule`() {
        // 打卡型与时长型排期完全一致：时长只改变记录的写法，不改变提醒时刻
        val toggle = task(completionMode = CareTaskCompletionMode.TOGGLE, reminderTimes = "09:00")
        val duration = task(completionMode = CareTaskCompletionMode.DURATION, reminderTimes = "09:00")

        assertEquals(duration.toDomainSchedule(), toggle.toDomainSchedule())
    }

    private fun task(
        scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        completionMode: CareTaskCompletionMode = CareTaskCompletionMode.TOGGLE,
        defaultDurationMinutes: Int? = null,
        timePeriods: String = "",
        reminderTimes: String = "08:00",
        intervalHours: Int = 0,
        frequencyType: String = "DAILY",
        frequencyInterval: Int = 1,
        frequencyDays: String = "",
    ) = CareTask(
        careRecipientId = 1L,
        title = "测试事项",
        category = CareTaskCategory.OTHER,
        completionMode = completionMode,
        defaultDurationMinutes = defaultDurationMinutes,
        scheduleKind = scheduleKind,
        timePeriods = timePeriods,
        reminderTimes = reminderTimes,
        intervalHours = intervalHours,
        frequencyType = frequencyType,
        frequencyInterval = frequencyInterval,
        frequencyDays = frequencyDays,
    )
}
