package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import java.time.LocalTime

enum class RoutineTimeSlot { WAKE, BREAKFAST, LUNCH, DINNER, BED }

data class RoutineTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        require(minute in 0..59) { "minute must be between 0 and 59" }
    }

    fun format24Hour(): String = "%02d:%02d".format(hour, minute)

    companion object {
        fun fromStoredOrDefault(hour: Int?, minute: Int?, default: RoutineTime): RoutineTime =
            if (hour in 0..23 && minute in 0..59) {
                RoutineTime(checkNotNull(hour), checkNotNull(minute))
            } else {
                default
            }
    }
}

data class RoutineSchedule(
    val wake: RoutineTime = RoutineTime(7, 0),
    val breakfast: RoutineTime = RoutineTime(8, 0),
    val lunch: RoutineTime = RoutineTime(12, 0),
    val dinner: RoutineTime = RoutineTime(18, 0),
    val bed: RoutineTime = RoutineTime(22, 0),
) {
    operator fun get(slot: RoutineTimeSlot): RoutineTime = when (slot) {
        RoutineTimeSlot.WAKE -> wake
        RoutineTimeSlot.BREAKFAST -> breakfast
        RoutineTimeSlot.LUNCH -> lunch
        RoutineTimeSlot.DINNER -> dinner
        RoutineTimeSlot.BED -> bed
    }

    fun withTime(slot: RoutineTimeSlot, time: RoutineTime): RoutineSchedule = when (slot) {
        RoutineTimeSlot.WAKE -> copy(wake = time)
        RoutineTimeSlot.BREAKFAST -> copy(breakfast = time)
        RoutineTimeSlot.LUNCH -> copy(lunch = time)
        RoutineTimeSlot.DINNER -> copy(dinner = time)
        RoutineTimeSlot.BED -> copy(bed = time)
    }
}

/**
 * 解析出的**未回绕**分钟数（本锚点自己那一天内的分钟偏移），可 `< 0` 或 `≥ 1440`。
 *
 * 这是全仓**唯一**做「起床/三餐/就寝 → 分钟」算术的地方；加偏移、判跨零点都在整数域，
 * `LocalTime` 的回绕只在 [resolve] 的最后一步出现（docs/medication-time-offset.md §2.3）。
 */
fun RoutineSchedule.resolveUnwrapped(anchor: RoutineAnchor): Int = when (anchor) {
    RoutineAnchor.MORNING -> wake.minuteOfDay
    RoutineAnchor.BEFORE_BREAKFAST -> breakfast.minuteOfDay - 15
    RoutineAnchor.AFTER_BREAKFAST -> breakfast.minuteOfDay + 15
    RoutineAnchor.BEFORE_LUNCH -> lunch.minuteOfDay - 15
    RoutineAnchor.AFTER_LUNCH -> lunch.minuteOfDay + 15
    RoutineAnchor.BEFORE_DINNER -> dinner.minuteOfDay - 15
    RoutineAnchor.AFTER_DINNER -> dinner.minuteOfDay + 15
    RoutineAnchor.AFTERNOON -> AFTERNOON_MINUTE_OF_DAY
    RoutineAnchor.EVENING -> bed.minuteOfDay - 60
    RoutineAnchor.BEDTIME -> bed.minuteOfDay
}

/**
 * 渲染钟点。`floorMod` 与 `LocalTime.plus/minusMinutes` 的回绕语义相同 ⇒ 对 0 偏移的
 * 既有输出**逐字不变**（祖父条款），只是把「回绕」收敛到这一个构造点。
 */
fun RoutineSchedule.resolve(anchor: RoutineAnchor): LocalTime =
    minuteOfDayToLocalTime(Math.floorMod(resolveUnwrapped(anchor), MINUTES_PER_DAY))

/**
 * 加偏移后的钟点：偏移**只在这里**与基线相加（docs/medication-time-offset.md §5.0）。
 *
 * `0` 直接返回 [resolve]（与旧实现逐字相同）；非 0 且解析后的瞬时落在 `0..1439` 之外即抛
 * [IllegalTimeOffsetException] —— 不钳位、不取整、不换方向。合法性由
 * [validateTimeOffset] 前置保证，这里是最后一道自检。
 */
fun RoutineSchedule.resolveWithOffset(anchor: RoutineAnchor, offsetMinutes: Int): LocalTime {
    if (offsetMinutes == 0) return resolve(anchor)
    val resolvedMinuteOfDay = resolveUnwrapped(anchor) + offsetMinutes
    if (resolvedMinuteOfDay !in 0 until MINUTES_PER_DAY) {
        throw IllegalTimeOffsetException(rejectedByResolvedInstant(anchor, offsetMinutes, resolvedMinuteOfDay))
    }
    return minuteOfDayToLocalTime(resolvedMinuteOfDay)
}

/** 整数分钟 → 钟点，唯一的 `LocalTime` 构造点（输入必须已在 `0..1439`）。 */
fun minuteOfDayToLocalTime(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

fun RoutineTime.toLocalTime(): LocalTime = LocalTime.of(hour, minute)

val RoutineTime.minuteOfDay: Int get() = hour * 60 + minute

const val MINUTES_PER_DAY: Int = 24 * 60

private const val AFTERNOON_MINUTE_OF_DAY: Int = 15 * 60
