package com.driezy.medlog.feature.careevents.reminder

import com.driezy.medlog.domain.CareEventInterval
import java.time.Instant
import java.time.ZoneId

/** 每日缺席型判定的结果（docs/tracked-events-spec.md §5）。 */
enum class CareEventReminderDecision {
    /** 具备资格且已发出（本函数只做判定时不会返回它之外的副作用）。 */
    NUDGE,

    /** 未选择成员。 */
    SKIP_NO_RECIPIENT,

    /** 该 kind 提醒开关为关（默认关，R1）。 */
    SKIP_DISABLED,

    /** 无锚点（该 kind 下无任何日志，R2）。 */
    SKIP_NO_ANCHOR,

    /** 距上次未达阈值（R4）。 */
    SKIP_BELOW_THRESHOLD,

    /** 当天设备本地时间尚未到 09:00（R13：早于窗口不发）。 */
    SKIP_BEFORE_WINDOW,

    /** 当天（设备本地日）已经提醒过（R6/R14 单调去重）。 */
    SKIP_ALREADY_NUDGED_TODAY,

    /** 发送前四要素再校验失败（成员/开关/锚点/阈值在决策后发生变化，R12）。 */
    SKIP_REVALIDATION_FAILED,
}

/** 一次判定所需的全部输入（纯数据，便于 JVM 单测）。 */
data class CareEventReminderInput(
    val enabled: Boolean,
    val thresholdDays: Int,
    val anchorMs: Long?,
    /** 上次提醒的设备本地 epochDay；null = 从未提醒。 */
    val nudgedDay: Long?,
    val nowMs: Long,
    val zoneId: ZoneId,
)

/**
 * 缺席型每日提醒的纯判定（docs/tracked-events-spec.md §5）。
 *
 * 无 Android、无副作用：只回答「此刻这一回该不该提醒」。真正的发送与「发送前再校验」在
 * [CareEventReminderEngine] 里。判定条件是「**设备本地日**内、设备本地时间已过 09:00、且当日尚未提醒」
 * ——窗口是「今天」而不是「09:00–09:15」（best-effort，R13）。
 */
object CareEventReminderPolicy {

    /** 推荐默认阈值天数（可改，R5/D5）。 */
    const val DEFAULT_THRESHOLD_DAYS = 3

    /** 每日窗口起点（设备本地小时）。早于该小时不提醒。 */
    const val WINDOW_START_HOUR = 9

    /** 从未提醒的哨兵：早于任何真实 epochDay（1970-01-01 为 0，真实日期远大于它）。 */
    const val NEVER_NUDGED = Long.MIN_VALUE

    /** 设备本地日序号（epochDay）。 */
    fun localEpochDay(nowMs: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().toEpochDay()

    fun decide(input: CareEventReminderInput): CareEventReminderDecision {
        if (!input.enabled) return CareEventReminderDecision.SKIP_DISABLED
        if (input.anchorMs == null) return CareEventReminderDecision.SKIP_NO_ANCHOR
        if (!CareEventInterval.isOverdue(input.anchorMs, input.nowMs, input.thresholdDays)) {
            return CareEventReminderDecision.SKIP_BELOW_THRESHOLD
        }
        val localHour = Instant.ofEpochMilli(input.nowMs).atZone(input.zoneId).hour
        if (localHour < WINDOW_START_HOUR) return CareEventReminderDecision.SKIP_BEFORE_WINDOW
        val today = localEpochDay(input.nowMs, input.zoneId)
        val last = input.nudgedDay ?: NEVER_NUDGED
        // 严格大于：时钟回拨/时区西移导致 today 回退时绝不重发（R14 单调）。
        if (today <= last) return CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY
        return CareEventReminderDecision.NUDGE
    }
}

/** 一次要发出的提醒内容（纯数据）。 */
data class CareEventNudge(val recipientId: Long, val kind: String, val daysSince: Long)
