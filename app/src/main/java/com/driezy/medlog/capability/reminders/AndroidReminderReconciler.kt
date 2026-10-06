package com.driezy.medlog.capability.reminders

import com.driezy.medlog.capability.widgets.WidgetRefresher
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.data.repository.CareTaskRepository
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
    private val careTasks: CareTaskRepository,
    private val careRecipients: CareRecipientRepository,
    private val logs: LogRepository,
    private val alarmScheduler: AlarmScheduler,
    private val notificationHelper: NotificationHelper,
    private val widgetRefresher: WidgetRefresher,
) : ReminderReconciler {
    override suspend fun reconcileMedication(id: MedicationId, reason: ReminderReconcileReason) {
        val medication = medications.getMedicationById(id.value)
        if (medication != null) {
            alarmScheduler.cancelAllAlarms(id.value, medication.careRecipientId)
        } else {
            // 行已硬删除：拿不到 careRecipientId，按 (type, id) 跨成员撤销残留登记项
            alarmScheduler.cancelAlarmsForMissingOwner(ReminderTargetType.MEDICATION, id.value).cancelNotifications()
        }
        notificationHelper.cancelAllReminderNotifications(id.value)
        if (medication != null && !medication.isArchived && !medication.isPRN) {
            schedule(medication)
        }
        widgetRefresher.refreshAll()
    }

    /**
     * 单条照护事项的重排：先取消该事项的全部闹钟与通知，再按数据库事实重建。
     *
     * 归档 / 删除 / `AS_NEEDED` 只做清理不排期（删除后 [careTasks.getTaskById] 返回 null）。
     * 与用药 [reconcileMedication] 同构。
     */
    override suspend fun reconcileCareTask(id: Long, reason: ReminderReconcileReason) {
        val task = careTasks.getTaskById(id)
        if (task != null) {
            alarmScheduler.cancelCareTaskAlarms(id, task.careRecipientId)
        } else {
            // 行已硬删除：拿不到 careRecipientId，按 (type, id) 跨成员撤销残留登记项
            alarmScheduler.cancelAlarmsForMissingOwner(ReminderTargetType.CARE_TASK, id).cancelNotifications()
        }
        notificationHelper.cancelCareTaskNotifications(id)
        if (task != null && !task.isArchived && task.scheduleKind != CareTaskScheduleKind.AS_NEEDED) {
            scheduleCareTask(task)
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
        val recipients = careRecipients.getRecipients()
        val recipientIds = recipients.map { it.id }
            .ifEmpty { listOf(ActiveRecipientStore.NO_RECIPIENT) }
        // 本轮真正存活的目标：成员存在且实体未归档 / 未按需。清理后重排会重新登记它们。
        val liveMedications = mutableSetOf<Long>()
        val liveCareTasks = mutableSetOf<Long>()
        recipientIds.forEach { recipientId ->
            alarmScheduler.cancelAlarmsFor(recipientId).cancelNotifications()
            // 用"含归档"的整份清单做清理：归档药品的残留通知也要收掉（改造前就是这么做的）
            medications.getAllMedicationsFor(recipientId).forEach { medication ->
                notificationHelper.cancelAllReminderNotifications(medication.id)
                if (!medication.isArchived && !medication.isPRN) {
                    liveMedications += medication.id
                    schedule(medication)
                }
            }
            // 照护事项与用药分段处理：同样用含归档清单清理残留通知，归档 / AS_NEEDED 只清理不排期
            careTasks.getAllTasksFor(recipientId).forEach { task ->
                notificationHelper.cancelCareTaskNotifications(task.id)
                if (!task.isArchived && task.scheduleKind != CareTaskScheduleKind.AS_NEEDED) {
                    liveCareTasks += task.id
                    scheduleCareTask(task)
                }
            }
        }
        // 自愈清理：成员已删除、实体已硬删除或已归档时，登记项不会被上面的"按成员重排"覆盖到，
        // 这里统一兑现并撤销，避免 phantom 提醒残留。存活目标原样保留，不误取消应保留的闹钟。
        val liveRecipientIds = recipients.map { it.id }.toSet()
        alarmScheduler.pruneOrphanedProjections { target ->
            target.recipientId in liveRecipientIds &&
                when (target.type) {
                    ReminderTargetType.MEDICATION -> target.id in liveMedications
                    ReminderTargetType.CARE_TASK -> target.id in liveCareTasks
                }
        }.cancelNotifications()
        widgetRefresher.refreshAll()
    }

    /** 被取消的目标按类型收起残留通知；用药与照护事项的编号空间各自独立。 */
    private fun List<ReminderTarget>.cancelNotifications() {
        forEach { target ->
            when (target.type) {
                ReminderTargetType.MEDICATION ->
                    notificationHelper.cancelAllReminderNotifications(target.id)
                ReminderTargetType.CARE_TASK ->
                    notificationHelper.cancelCareTaskNotifications(target.id)
            }
        }
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

    /**
     * 按数据库事实为一条照护事项排期。
     *
     * - `INTERVAL`（完成后计时）以最后一条 `DONE` 的 `actualEndMs` 为锚点（照护事项版的 `lastTaken`）；
     * - `handled` 取 `DONE` / `SKIPPED` 的时间槽，避免为已处理的槽重复排提醒；
     * - `AS_NEEDED` 不产生任何时刻（由调用方过滤，这里同样自然为空）。
     */
    private suspend fun scheduleCareTask(task: CareTask) {
        val recorded = careTasks.getLogsForTask(task.id).first()
        val handled = recorded.filter {
            it.status == CareTaskLogStatus.DONE || it.status == CareTaskLogStatus.SKIPPED
        }
        val lastDoneMs = recorded
            .filter { it.status == CareTaskLogStatus.DONE && it.actualEndMs != null }
            .maxByOrNull { it.scheduledTimeMs }
            ?.actualEndMs
        alarmScheduler.scheduleCareTaskReminders(
            task,
            lastDoneMs,
            handled.map { Instant.ofEpochMilli(it.scheduledTimeMs) }.toSet(),
        )
    }
}
