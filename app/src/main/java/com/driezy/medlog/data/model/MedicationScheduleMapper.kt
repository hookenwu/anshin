package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.MedicationSchedule
import com.driezy.medlog.domain.model.RoutineAnchor
import com.driezy.medlog.domain.model.ScheduleRecurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Keeps legacy Room string encodings at the persistence boundary. */
fun Medication.toDomainSchedule(): MedicationSchedule {
    if (isPRN) return MedicationSchedule.AsNeeded
    if (intervalHours > 0) return MedicationSchedule.Interval(Duration.ofHours(intervalHours.toLong()))

    val recurrence = toDomainRecurrence()
    val periods = TimePeriods.parse(timePeriod)
    val fallback = LocalTime.of(reminderHour.coerceIn(0, 23), reminderMinute.coerceIn(0, 59))
    val persistedTimes = reminderTimes
        .split(',')
        .mapNotNull { raw -> runCatching { LocalTime.parse(raw.trim()) }.getOrNull() }

    // 多个用餐时段：保存时已按时段各换算出一个钟点，这里按钟点升序展开为多槽位，
    // 直接复用既有的「一天多次提醒 / 逐槽打卡 / 库存扣减」链路。
    if (periods.size > 1) {
        return MedicationSchedule.ExactTimes(
            times = persistedTimes.ifEmpty { listOf(fallback) }.distinct().sorted(),
            recurrence = recurrence,
        )
    }

    val period = periods.firstOrNull()
    if (period != null) {
        return MedicationSchedule.RoutineAnchored(
            anchor = RoutineAnchor.valueOf(period.name),
            resolvedTime = persistedTimes.firstOrNull() ?: fallback,
            recurrence = recurrence,
        )
    }

    val times = persistedTimes.ifEmpty { listOf(fallback) }
    return MedicationSchedule.ExactTimes(times = times.distinct(), recurrence = recurrence)
}

/** Converts a typed routine result back to the legacy Room columns in one persistence adapter. */
fun Medication.withResolvedRoutineTime(time: LocalTime): Medication {
    val encoded = time.format(STORED_TIME_FORMATTER)
    return copy(
        reminderTimes = encoded,
        reminderHour = time.hour,
        reminderMinute = time.minute,
    )
}

/**
 * 多用餐时段版本：把每个时段换算出的钟点整体写回 `reminderTimes`（升序去重），
 * 主提醒列保留最早的一个（通知调度的向后兼容字段）。
 */
fun Medication.withResolvedRoutineTimes(times: List<LocalTime>): Medication {
    if (times.isEmpty()) return this
    val sorted = times.distinct().sorted()
    val earliest = sorted.first()
    return copy(
        reminderTimes = sorted.joinToString(",") { it.format(STORED_TIME_FORMATTER) },
        reminderHour = earliest.hour,
        reminderMinute = earliest.minute,
    )
}

/** Resolves a stable wall-clock slot without leaking legacy comma-separated fields to commands. */
fun Medication.scheduledLocalTimeForSlot(index: Int): LocalTime {
    val fallback = LocalTime.of(reminderHour.coerceIn(0, 23), reminderMinute.coerceIn(0, 59))
    return when (val schedule = toDomainSchedule()) {
        is MedicationSchedule.ExactTimes -> schedule.times.getOrNull(index) ?: fallback
        is MedicationSchedule.RoutineAnchored -> schedule.resolvedTime.takeIf { index == 0 } ?: fallback
        is MedicationSchedule.Interval, MedicationSchedule.AsNeeded -> fallback
    }
}

/**
 * 该药一天里最早的一次服药时间，用作列表排序键。
 *
 * 取精确时间集合的最小值（多时段/多时间点都归约到这里），作息时段用已换算的钟点，
 * 间隔给药与按需用药没有固定钟点，回落到主提醒时间。
 */
fun Medication.earliestScheduledTime(): LocalTime {
    val fallback = LocalTime.of(reminderHour.coerceIn(0, 23), reminderMinute.coerceIn(0, 59))
    return when (val schedule = toDomainSchedule()) {
        is MedicationSchedule.ExactTimes -> schedule.times.minOrNull() ?: fallback
        is MedicationSchedule.RoutineAnchored -> schedule.resolvedTime
        is MedicationSchedule.Interval, MedicationSchedule.AsNeeded -> fallback
    }
}

private fun Medication.toDomainRecurrence(): ScheduleRecurrence = when (frequencyType) {
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

private val STORED_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
