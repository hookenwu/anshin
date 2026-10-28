package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用例：当用户更改作息时间（起床、早/午/晚餐、就寝）后，
 * 为所有作息锚点药品重新计算类型化计划，更新数据库并重新调度闹钟。
 *
 * **这里没有任何时间算术、没有任何"哪些药要改"的判定**（docs/medication-time-offset.md §3.5 / §5.0.1）：
 * 读快照、逐药经唯一的写入口闸门 `gatedFor` 物化、判定"是否真的变了"、写 `updatePlan`——
 * 全都在 [MedicationRepository.planForRoutine] 的**同一个事务**里完成，一个 `changedAt` 全批共用。
 * 用例只负责两件事：调用那一个批量入口，**提交之后**重排提醒。
 *
 * 失败语义（§9 ①）：
 * - 冲突（有药的偏移在新作息下不可物化）⇒ `RoutineResyncConflictException` **原样穿出**，整批已回滚；
 * - 时钟没越过边界 ⇒ `PlanBoundaryNotAdvancedException` 原样穿出；
 * - 这里**不做 `runCatching`**：吞掉它会得到"作息已改、钟点永久旧、无人知道"。
 *   `reconcile` 只在提交成功后执行（异常在它之前穿出 ⇒ 闹钟保持旧时刻，与 DB 逐字一致）。
 *
 * 不处理：精确时间（无作息时段，钟点由用户手动指定）、PRN、间隔给药——它们在批内被直接跳过。
 */
@Singleton
class ResyncRemindersUseCase @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val reconcileReminders: ReconcileRemindersUseCase,
) {
    suspend operator fun invoke(schedule: RoutineSchedule) {
        medicationRepository.planForRoutine(schedule)
        // 提交之后、不在事务内：reconcile 自身 runCatching + 入队重试（ReminderReconciliation.kt:14-39）
        reconcileReminders.all(ReminderReconcileReason.ROUTINE_CHANGED)
    }
}
