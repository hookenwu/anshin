package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.TimePeriods
import com.driezy.medlog.data.model.resolve
import com.driezy.medlog.data.model.withResolvedRoutineTimes
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.RoutineAnchor
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用例：当用户更改作息时间（起床、早/午/晚餐、就寝）后，
 * 自动为所有作息锚点药品重新计算类型化计划，
 * 更新数据库并重新调度闹钟。
 *
 * 一个药品可以挂多个用餐时段（`timePeriod` 为逗号分隔的 key 列表），
 * 因此这里按整个列表重算并整体回写 `reminderTimes`，而不是只重算第一个时段。
 *
 * 调用时机：
 *  - SettingsViewModel.updateRoutineTime() 保存成功后
 *
 * 不处理：
 *  - 精确时间（无作息时段）— 提醒时间由用户手动指定，不自动覆盖
 *  - isPRN / 间隔给药药品 — 没有固定钟点
 */
@Singleton
class ResyncRemindersUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val activeRecipient: ActiveRecipientStore,
    private val reconcileReminders: ReconcileRemindersUseCase,
) {
    /**
     * 传入最新的 [schedule]，对所有活跃（未归档）、非 PRN、非间隔给药且有作息时段的药品：
     * 1. 根据每个作息锚点重新计算时间
     * 2. 更新数据库
     * 3. 取消旧闹钟 → 调度新闹钟
     */
    suspend operator fun invoke(schedule: RoutineSchedule) {
        // 作息现在是成员级的：只重算"当前成员"的药品；尚未选成员时保持改造前的整机行为。
        val recipientId = activeRecipient.current()
        val meds = if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            medicationRepository.getActiveMedications().first()
        } else {
            medicationRepository.getMedicationsFor(recipientId)
        }
        val updates = meds.mapNotNull { med ->
            if (med.isPRN || med.intervalHours > 0) return@mapNotNull null
            val anchors = TimePeriods.parse(med.timePeriod)
                .mapNotNull { period -> runCatching { RoutineAnchor.valueOf(period.name) }.getOrNull() }
            if (anchors.isEmpty()) return@mapNotNull null
            val resolved = anchors.map(schedule::resolve).distinct().sorted()
            if (resolved == med.storedReminderTimes()) return@mapNotNull null
            med.withResolvedRoutineTimes(resolved)
        }
        if (updates.isNotEmpty()) medicationRepository.updateMedications(updates)
        reconcileReminders.all(ReminderReconcileReason.ROUTINE_CHANGED)
    }
}

private fun com.driezy.medlog.data.model.Medication.storedReminderTimes(): List<LocalTime> = reminderTimes.split(',')
    .mapNotNull { raw -> runCatching { LocalTime.parse(raw.trim()) }.getOrNull() }
    .distinct()
    .sorted()
