package com.driezy.medlog.capability.reminders

import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.feature.medications.application.MedicationStatus
import com.driezy.medlog.feature.medications.application.MedicationStatusDerivation
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** 发送前派生校验的裁决（docs/medication-status-spec.md §7）。 */
enum class ReminderDecision { ALLOW, SUPPRESS }

/**
 * **发送前派生校验守卫**（更正 N，首期交付）——docs/medication-status-spec.md §7 / §14 T10 / §16.20。
 *
 * 三类用药闹钟在真正弹通知前再读一次**派生状态**（`statusAt`，**读事件、不读缓存**）：
 * 非 `ACTIVE`（含 `PAUSED`、`UNKNOWN`）一律抑制且**不续排**下一次。
 *
 * 它是「缓存与事件不一致 / 取消漏跑」这一整类的**唯一**兜底：陈旧缓存也无法击穿它，
 * 因为判定只读事件、无任何缓存回落（更正 I）。
 * `ACTION_TAKEN` / `ACTION_SKIP` **不**经本守卫（真实服药记录不得被拦）。
 */
@Singleton
class MedicationReminderGate @Inject constructor(
    private val statusRepository: MedicationStatusRepository,
    private val clock: Clock,
) {
    suspend fun decide(medicationId: Long): ReminderDecision {
        val events = statusRepository.getEventsOnce(medicationId)
        return if (MedicationStatusDerivation.statusAt(events, clock.millis()) == MedicationStatus.ACTIVE) {
            ReminderDecision.ALLOW
        } else {
            ReminderDecision.SUPPRESS
        }
    }
}
