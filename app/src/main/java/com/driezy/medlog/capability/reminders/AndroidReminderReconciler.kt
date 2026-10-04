package com.driezy.medlog.capability.reminders

import com.driezy.medlog.capability.widgets.WidgetRefresher
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.data.repository.LogRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.ReminderReconciler
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidReminderReconciler @Inject constructor(
    private val medications: MedicationRepository,
    private val careRecipients: CareRecipientRepository,
    private val logs: LogRepository,
    private val alarmScheduler: AlarmScheduler,
    private val notificationHelper: NotificationHelper,
    private val widgetRefresher: WidgetRefresher,
) : ReminderReconciler {
    override suspend fun reconcileMedication(id: MedicationId, reason: ReminderReconcileReason) {
        val medication = medications.getMedicationById(id.value)
        alarmScheduler.cancelAllAlarms(
            id.value,
            medication?.careRecipientId ?: ActiveRecipientStore.NO_RECIPIENT,
        )
        notificationHelper.cancelAllReminderNotifications(id.value)
        if (medication != null && !medication.isArchived && !medication.isPRN) {
            schedule(medication)
        }
        widgetRefresher.refreshAll()
    }

    /**
     * 全量重排，按成员逐个进行。
     *
     * 阶段 0 的实现是"取消所有已登记闹钟 → 只为当前成员重排"，切成员会清掉别人的闹钟；
     * 现在每位成员只清理并重建自己的那部分，互不影响。
     */
    override suspend fun reconcileAll(reason: ReminderReconcileReason) {
        alarmScheduler.refreshRecipientCaches()
        // 阶段 0 遗留的、无法判定归属的登记项一次性作废（紧随其后的重排会重建）
        alarmScheduler.cancelUnattributedAlarms().cancelNotifications()
        val recipientIds = careRecipients.getRecipients()
            .map { it.id }
            .ifEmpty { listOf(ActiveRecipientStore.NO_RECIPIENT) }
        recipientIds.forEach { recipientId ->
            alarmScheduler.cancelAlarmsFor(recipientId).cancelNotifications()
            // 用"含归档"的整份清单做清理：归档药品的残留通知也要收掉（改造前就是这么做的）
            medications.getAllMedicationsFor(recipientId).forEach { medication ->
                notificationHelper.cancelAllReminderNotifications(medication.id)
                if (!medication.isArchived && !medication.isPRN) {
                    schedule(medication)
                }
            }
        }
        widgetRefresher.refreshAll()
    }

    /** 被取消的目标里只有用药有通知要收；照护事项的通知在 T3 接入提醒通道时补齐。 */
    private fun List<ReminderTarget>.cancelNotifications() {
        filter { it.type == ReminderTargetType.MEDICATION }
            .forEach { notificationHelper.cancelAllReminderNotifications(it.id) }
    }

    private suspend fun schedule(medication: com.driezy.medlog.data.model.Medication) {
        val recorded = logs.getLogsForMedication(medication.id, limit = Int.MAX_VALUE).first()
        val handled = recorded.filter { it.status in setOf(LogStatus.TAKEN, LogStatus.PARTIAL, LogStatus.SKIPPED) }
        val lastTaken = handled.filter {
            it.scheduledTimeMs >= medication.planEffectiveFromMs &&
                it.actualTakenTimeMs != null
        }.maxByOrNull { it.scheduledTimeMs }?.actualTakenTimeMs
        alarmScheduler.scheduleAllReminders(
            medication,
            lastTaken,
            handled.map { Instant.ofEpochMilli(it.scheduledTimeMs) }.toSet(),
        )
    }
}
