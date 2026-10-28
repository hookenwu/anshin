package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import java.time.LocalTime

/**
 * 单药提醒时间偏移的**唯一**校验结果（docs/medication-time-offset.md §2.2）。
 *
 * 判定输入是「解析后的瞬时」= `RoutineSchedule.resolveUnwrapped(anchor) + minutes`，而不是库里的原始数字：
 * 这样既能识别「同日基线 + 偏移越过 24:00」，也能识别「基线本身已回绕」。
 */
sealed interface TimeOffsetCheck {
    data class Allowed(val minutes: Int) : TimeOffsetCheck

    data class Rejected(
        /** 被拒绝的档位（分钟）。 */
        val minutes: Int,
        /** 触发拒绝的锚点（多时段取交集：指明是哪一段）。null = 该药没有可解析基线（精确时间）。 */
        val anchor: RoutineAnchor?,
        /** 该锚点在本作息下的基线钟点（渲染值，可能已回绕）。null = 没有基线。 */
        val base: LocalTime?,
        /** `baseUnwrapped + minutes`，即"解析后的瞬时"；没有基线时为该档位本身。 */
        val resolvedMinuteOfDay: Int,
        val reason: Reason,
    ) : TimeOffsetCheck

    enum class Reason { NOT_MULTIPLE_OF_15, OUT_OF_RANGE, CROSS_MIDNIGHT, WRAPPED_ANCHOR, NOT_APPLICABLE }
}

/**
 * 偏移不合法。**已定义类型**（不是 `require`）：调用方按 [TimeOffsetCheck.Rejected.reason] 区分
 * "越界"、"跨零点"、"非适格"与"基线已回绕"，并原样报给用户。
 *
 * 写入口必须在**任何写入之前**抛出它 ⇒ 事务整体回滚、无半行数据（§3.5）。
 * 绝不钳位、绝不取整、绝不按方向自动纠正。
 */
class IllegalTimeOffsetException(val check: TimeOffsetCheck.Rejected) :
    IllegalArgumentException(
        "Illegal time offset ${check.minutes} at ${check.anchor}: ${check.reason} " +
            "(base ${check.base}, resolved minuteOfDay ${check.resolvedMinuteOfDay})",
    )

/** 合法档位上限：`|minutes| <= 60`、步长 15（§2.2 ①②）。 */
const val MAX_TIME_OFFSET_MINUTES: Int = 60
private const val OFFSET_STEP_MINUTES: Int = 15

/**
 * 唯一校验函数（pure，无 Android 依赖）。
 *
 * 判定顺序（§2.2；"回绕"先于"跨零点"，依据 §2.3 的「不看数值和是否在区间内」）：
 * 1. 非 15 的倍数 ⇒ `NOT_MULTIPLE_OF_15`；
 * 2. `|minutes| > 60` ⇒ `OUT_OF_RANGE`；
 * 3. `minutes == 0` ⇒ `Allowed`（祖父条款：0 偏移永远合法）；
 * 4. 非适格（PRN / 间隔给药）或没有可解析基线，且 `minutes != 0` ⇒ `NOT_APPLICABLE`；
 * 5. 任一锚点的**未回绕基线**落在 `0..1439` 之外 ⇒ `WRAPPED_ANCHOR`（基线自己已跨日，加任何非 0
 *    偏移都只会把这个错误放大 ⇒ 直接拒绝）；
 * 6. 任一锚点满足 `baseUnwrapped + minutes ∉ 0..1439` ⇒ `CROSS_MIDNIGHT`（多时段取交集）。
 */
fun validateTimeOffset(
    minutes: Int,
    schedule: RoutineSchedule,
    periods: List<RoutineAnchor>,
    applicable: Boolean,
): TimeOffsetCheck {
    if (minutes % OFFSET_STEP_MINUTES != 0) {
        return rejected(minutes, null, schedule, periods, TimeOffsetCheck.Reason.NOT_MULTIPLE_OF_15)
    }
    if (minutes > MAX_TIME_OFFSET_MINUTES || minutes < -MAX_TIME_OFFSET_MINUTES) {
        return rejected(minutes, null, schedule, periods, TimeOffsetCheck.Reason.OUT_OF_RANGE)
    }
    if (minutes == 0) return TimeOffsetCheck.Allowed(minutes)
    if (!applicable || periods.isEmpty()) {
        return rejected(minutes, null, schedule, periods, TimeOffsetCheck.Reason.NOT_APPLICABLE)
    }
    val wrapped = periods.firstOrNull { schedule.resolveUnwrapped(it) !in 0 until MINUTES_PER_DAY }
    if (wrapped != null) {
        return rejected(minutes, wrapped, schedule, periods, TimeOffsetCheck.Reason.WRAPPED_ANCHOR)
    }
    val crossing = periods.firstOrNull { schedule.resolveUnwrapped(it) + minutes !in 0 until MINUTES_PER_DAY }
    if (crossing != null) {
        return rejected(minutes, crossing, schedule, periods, TimeOffsetCheck.Reason.CROSS_MIDNIGHT)
    }
    return TimeOffsetCheck.Allowed(minutes)
}

/** 写入口的统一闸门：合法则返回档位，非法则抛 [IllegalTimeOffsetException]。 */
fun requireAllowed(check: TimeOffsetCheck): Int = when (check) {
    is TimeOffsetCheck.Allowed -> check.minutes
    is TimeOffsetCheck.Rejected -> throw IllegalTimeOffsetException(check)
}

private fun rejected(
    minutes: Int,
    anchor: RoutineAnchor?,
    schedule: RoutineSchedule,
    periods: List<RoutineAnchor>,
    reason: TimeOffsetCheck.Reason,
): TimeOffsetCheck.Rejected {
    // 没有具名锚点时（语法/适格性拒绝），展示值与瞬时取自首个时段（可能没有）。
    val shown = anchor ?: periods.firstOrNull()
    return TimeOffsetCheck.Rejected(
        minutes = minutes,
        anchor = shown,
        base = shown?.let { schedule.resolve(it) },
        resolvedMinuteOfDay = shown?.let { schedule.resolveUnwrapped(it) + minutes } ?: minutes,
        reason = reason,
    )
}

/**
 * [RoutineSchedule.resolveWithOffset] 的最后一道自检：解析后的瞬时越界。
 * 正常路径由 [validateTimeOffset] 拦下，走到这里说明调用方绕过了校验。
 */
internal fun rejectedByResolvedInstant(
    anchor: RoutineAnchor,
    offsetMinutes: Int,
    resolvedMinuteOfDay: Int,
): TimeOffsetCheck.Rejected = TimeOffsetCheck.Rejected(
    minutes = offsetMinutes,
    anchor = anchor,
    base = minuteOfDayToLocalTime(Math.floorMod(resolvedMinuteOfDay - offsetMinutes, MINUTES_PER_DAY)),
    resolvedMinuteOfDay = resolvedMinuteOfDay,
    reason = TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
)
