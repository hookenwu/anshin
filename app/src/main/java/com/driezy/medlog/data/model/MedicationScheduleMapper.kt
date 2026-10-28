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

/** `TimePeriod` → 语义锚点：除 `EXACT` 外与 [RoutineAnchor] **同名 1:1**（`key` 只用于持久化）。 */
fun TimePeriod.toAnchorOrNull(): RoutineAnchor? = if (this == TimePeriod.EXACT) null else RoutineAnchor.valueOf(name)

/**
 * 写路径的**唯一物化点**（docs/medication-time-offset.md §5.0.1）：
 * 校验（`requireAllowed`）→ 逐锚点 `resolveWithOffset`（**全仓唯一**把偏移与基线相加的地方）
 * → 升序去重写回 `reminderTimes / reminderHour / reminderMinute`。
 *
 * 非适格药品（PRN / 间隔给药）与没有可解析基线的药品（精确时间）**原样返回**：它们的钟点是用户
 * 指定的绝对值，偏移恒 0（非 0 会被 `validateTimeOffset` 判 `NOT_APPLICABLE` 并拒绝）。
 *
 * 幂等：同一 `(作息, 时段, 偏移)` 连算两次结果逐字相同；非法偏移抛
 * `IllegalTimeOffsetException`（绝不钳位）。
 *
 * @param offsetOverride 仅「将偏移归零并继续 / 并恢复」这两个用户显式动作使用；其余写入口传 `null`
 *   （= 库里已校验的值）。生效值会写回 `offsetMinutes`，否则下一次重算又按旧偏移冲突。
 *
 * **它本身不是写入口的闸门**：函数开头的两处早退发生在 `requireAllowed` **之前**，所以 PRN / 间隔给药 /
 * 精确时间行上的非 0 偏移会原样穿过去。写入口必须调 [gatedFor]。
 */
fun Medication.materializedFor(schedule: RoutineSchedule, offsetOverride: Int? = null): Medication {
    if (isPRN || intervalHours > 0) return this
    val anchors = TimePeriods.parse(timePeriod).mapNotNull { period -> period.toAnchorOrNull() }
    if (anchors.isEmpty()) return this
    val offset = requireAllowed(
        validateTimeOffset(offsetOverride ?: offsetMinutes, schedule, anchors, applicable = true),
    )
    return withResolvedRoutineTimes(anchors.map { anchor -> schedule.resolveWithOffset(anchor, offset) })
        .copy(offsetMinutes = offset)
}

/**
 * 写入口的**统一闸门**（PLAN §4.2 / INV-4）：把「适格性判定」与「合法性判定」合成一次调用，
 * 适格行再委托 [materializedFor] 物化，两者刻意放在同一文件里成对存在。
 *
 * 为什么不能只调 [materializedFor]：它在 `isPRN || intervalHours > 0` 与 `anchors.isEmpty()` 两处
 * **先早退、后校验**，所以非适格行上的 `offsetMinutes = 15` 会一字不改地落库。这里的
 * `validateTimeOffset(..., applicable = false)`（非适格行）是**唯一**会拒掉它的地方，而且发生在任何写入之前
 * （抛 [IllegalTimeOffsetException]，不钳位、不归零，事务整体回滚）。
 *
 * 本批不接任何业务入口：编辑器、resync、取消归档、恢复、导入各自在自己的批次里改调它。
 */
fun Medication.gatedFor(schedule: RoutineSchedule, offsetOverride: Int? = null): Medication {
    val anchors = TimePeriods.parse(timePeriod).mapNotNull { period -> period.toAnchorOrNull() }
    val applicable = !isPRN && intervalHours <= 0 && anchors.isNotEmpty()
    val offset = offsetOverride ?: offsetMinutes
    requireAllowed(validateTimeOffset(offset, schedule, anchors, applicable))
    // 非适格行不物化，但「归零」这类覆盖仍要落到行上，否则库里会留下永久非 0 的非法值。
    return if (applicable) materializedFor(schedule, offsetOverride) else copy(offsetMinutes = offset)
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
