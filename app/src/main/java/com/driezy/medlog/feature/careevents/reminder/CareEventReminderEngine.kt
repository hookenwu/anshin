package com.driezy.medlog.feature.careevents.reminder

import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.domain.CareEventInterval
import java.time.Clock
import java.time.ZoneId

/**
 * 判定所需的可读/可写状态。抽象出来便于在 JVM 上穷举提醒规则（含「发送前再读一次」）。
 */
interface CareEventReminderStateSource {
    suspend fun activeRecipientId(): Long
    suspend fun isEnabled(recipientId: Long, kind: String): Boolean
    suspend fun thresholdDays(recipientId: Long, kind: String): Int

    /** 最新一条 `occurredAtMs`（锚点）；无记录返回 null。 */
    suspend fun newestAnchorMs(recipientId: Long, kind: String): Long?

    /** 上次提醒的设备本地 epochDay；从未提醒返回 null。 */
    suspend fun nudgedDay(recipientId: Long, kind: String): Long?

    /** 记录「今天已提醒」。 */
    suspend fun markNudged(recipientId: Long, kind: String, epochDay: Long)
}

/**
 * 缺席型每日提醒的编排（docs/tracked-events-spec.md §5 R12/R11）。
 *
 * 流程：读一次状态快照 → [CareEventReminderPolicy.decide] → 若为 [CareEventReminderDecision.NUDGE]，
 * **发送前再读一次四要素**（① 当前活跃成员仍为该成员 ② 开关仍开 ③ 最新锚点未变 ④ 阈值未变，
 * 外加 R6 的当日去重）——任一变化即放弃；否则发送并把当日标记落库。
 *
 * **写入（record/edit/delete）不会调用本引擎**：历史变更只重算派生状态，绝不即时通知（R11）。
 * 引擎只由既有周期 worker 与既有接收器触发（R9）。
 */
class CareEventReminderEngine(
    private val state: CareEventReminderStateSource,
    private val clock: Clock,
    private val zoneProvider: () -> ZoneId,
    private val send: (CareEventNudge) -> Unit,
) {
    suspend fun check(kind: String = CareEventKind.BOWEL): CareEventReminderDecision {
        val recipientId = state.activeRecipientId()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return CareEventReminderDecision.SKIP_NO_RECIPIENT

        val now = clock.millis()
        val zone = zoneProvider()
        val anchor = state.newestAnchorMs(recipientId, kind)
        val threshold = state.thresholdDays(recipientId, kind)
        val enabled = state.isEnabled(recipientId, kind)
        val nudgedDay = state.nudgedDay(recipientId, kind)

        val decision = CareEventReminderPolicy.decide(
            CareEventReminderInput(
                enabled = enabled,
                thresholdDays = threshold,
                anchorMs = anchor,
                nudgedDay = nudgedDay,
                nowMs = now,
                zoneId = zone,
            ),
        )
        if (decision != CareEventReminderDecision.NUDGE) return decision

        // ── R12：发送前四要素再校验 ──────────────────────────────────────────
        if (state.activeRecipientId() != recipientId) return CareEventReminderDecision.SKIP_REVALIDATION_FAILED
        if (!state.isEnabled(recipientId, kind)) return CareEventReminderDecision.SKIP_REVALIDATION_FAILED
        if (state.newestAnchorMs(recipientId, kind) != anchor) return CareEventReminderDecision.SKIP_REVALIDATION_FAILED
        if (state.thresholdDays(recipientId, kind) !=
            threshold
        ) {
            return CareEventReminderDecision.SKIP_REVALIDATION_FAILED
        }
        // R6 第五道闸门：当日去重（再读一次标记）。
        val today = CareEventReminderPolicy.localEpochDay(now, zone)
        val last = state.nudgedDay(recipientId, kind) ?: CareEventReminderPolicy.NEVER_NUDGED
        if (today <= last) return CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY

        send(
            CareEventNudge(
                recipientId = recipientId,
                kind = kind,
                daysSince =
                CareEventInterval.wholeDaysSince(anchor, now) ?: 0L,
            ),
        )
        state.markNudged(recipientId, kind, today)
        return CareEventReminderDecision.NUDGE
    }
}
