package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.MedicationSchedule
import com.driezy.medlog.domain.model.RoutineAnchor
import com.driezy.medlog.domain.model.ScheduleRecurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * CareTask → 既有排期领域模型（docs/care-tasks.md §2.1）。
 *
 * 照护事项与用药共用同一套排期/提醒底座：映射产出 [MedicationSchedule]，
 * 于是重排、闹钟分槽、「完成后计时顺延」（`nextOccurrenceForSlot(afterMs = 上次完成)`）
 * 等能力原样复用，**不新建发生器**。
 *
 * 与用药映射的差别：照护事项用类型化枚举而非遗留字符串列，因此没有"单值兼容"分支；
 * 也没有主提醒列（reminderHour/Minute）可回落，未配置时间时用 [DEFAULT_REMINDER_TIME]。
 */
fun CareTask.toDomainSchedule(): MedicationSchedule {
    if (scheduleKind == CareTaskScheduleKind.AS_NEEDED) return MedicationSchedule.AsNeeded

    // 完成后计时（如翻身每 2 小时）：领域层 Interval 由排期器按上次完成时间展开
    if (scheduleKind == CareTaskScheduleKind.INTERVAL) {
        return MedicationSchedule.Interval(Duration.ofHours(intervalHours.coerceAtLeast(1).toLong()))
    }

    val recurrence = toDomainRecurrence()
    val times = parsedReminderTimes().ifEmpty { listOf(DEFAULT_REMINDER_TIME) }
    val periods = TimePeriods.parse(timePeriods)

    // 多个作息时段：保存时已按时段各换算出一个钟点，展开为多槽位（与用药同一约定）
    if (periods.size > 1) {
        return MedicationSchedule.ExactTimes(times = times.distinct().sorted(), recurrence = recurrence)
    }

    val period = periods.firstOrNull()
    if (period != null) {
        return MedicationSchedule.RoutineAnchored(
            anchor = RoutineAnchor.valueOf(period.name),
            resolvedTime = times.first(),
            recurrence = recurrence,
        )
    }

    return MedicationSchedule.ExactTimes(times = times.distinct(), recurrence = recurrence)
}

/**
 * 完成一次之后，下一次提醒的间隔。
 *
 * 用于两类场景：INTERVAL 型（翻身）本身；以及「固定时段 + 间隔补做」的混用
 * （决策 3：两种策略可在同一事项上混用）。AS_NEEDED 或未配置间隔时为 null。
 */
fun CareTask.completionInterval(): Duration? = intervalHours
    .takeIf { it > 0 && scheduleKind != CareTaskScheduleKind.AS_NEEDED }
    ?.let { Duration.ofHours(it.toLong()) }

/** 一天里最早的一次时间，用作今日页时间轴的排序键（对齐用药的 `earliestScheduledTime()`）。 */
fun CareTask.earliestScheduledTime(): LocalTime = when (val schedule = toDomainSchedule()) {
    is MedicationSchedule.ExactTimes -> schedule.times.minOrNull() ?: DEFAULT_REMINDER_TIME
    is MedicationSchedule.RoutineAnchored -> schedule.resolvedTime
    is MedicationSchedule.Interval, MedicationSchedule.AsNeeded -> DEFAULT_REMINDER_TIME
}

private fun CareTask.toDomainRecurrence(): ScheduleRecurrence = when (frequencyType.lowercase()) {
    "interval" -> ScheduleRecurrence.EveryDays(frequencyInterval.coerceAtLeast(1))
    "specific_days" -> {
        val days = frequencyDays
            .split(',')
            .mapNotNull(String::trim)
            .mapNotNull(String::toIntOrNull)
            .filter { it in 1..7 }
            .map(DayOfWeek::of)
            .toSet()
        if (days.isEmpty()) ScheduleRecurrence.Daily else ScheduleRecurrence.Weekdays(days)
    }
    else -> ScheduleRecurrence.Daily
}

private fun CareTask.parsedReminderTimes(): List<LocalTime> = reminderTimes
    .split(',')
    .mapNotNull { raw -> runCatching { LocalTime.parse(raw.trim()) }.getOrNull() }

/** 未配置任何时间时的兜底钟点（照护事项没有用药那样的主提醒列）。 */
internal val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(8, 0)
