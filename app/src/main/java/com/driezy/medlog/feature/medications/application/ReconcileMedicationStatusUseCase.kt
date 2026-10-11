package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusPreferences
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import java.time.Clock
import javax.inject.Inject

/**
 * 系统自动核对（AUTO RECONCILE）——docs/medication-status-spec.md §8.1。
 *
 * 对当前成员逐药：`derived = deriveCurrentStatus(events, now)`（**只读事件、含基线行；不读缓存**）；
 * 若 `derived != cache` → 直写缓存列；若由活跃翻为暂停 → 触发一次提醒重排；收尾写
 * **`lastAutoReconciledAtMs`**（系统事实）。
 *
 * 硬约束：**不产生状态事件**；**不写 `medication_logs`**；**不写 `lastUserCheckedDate`**（§8.1）。
 */
class ReconcileMedicationStatusUseCase @Inject constructor(
    private val medications: MedicationRepository,
    private val statusRepository: MedicationStatusRepository,
    private val preferences: MedicationStatusPreferences,
    private val activeRecipient: ActiveRecipientStore,
    private val reconcileReminders: ReconcileRemindersUseCase,
    private val clock: Clock,
) {

    /** 全量自动核对（应用启动 / 小组件 tick / 开机 触发）。 */
    suspend fun reconcile() {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return
        val events = statusRepository.getEventsForRecipientOnce(recipientId)
        val byMedication = events.groupBy { it.medicationId }
        val now = clock.millis()
        medications.getAllMedicationsFor(recipientId).forEach { medication ->
            val derived = MedicationStatusDerivation.deriveCurrentStatus(byMedication[medication.id].orEmpty(), now)
            if (derived != medication.isArchived) {
                medications.setArchivedCache(medication.id, derived)
                if (derived) {
                    runCatching {
                        reconcileReminders.medication(
                            MedicationId(medication.id),
                            ReminderReconcileReason.MEDICATION_CHANGED,
                        )
                    }
                }
            }
        }
        preferences.setAutoReconciledAtMs(recipientId, now)
    }
}
