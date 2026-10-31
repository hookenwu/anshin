package com.driezy.medlog.data.model

/**
 * `updatePlan` 的**纯**判定结果（docs/medication-time-offset.md §3.2）。
 *
 * 从 DAO 里抽出来是为了让「这次保存到底会不会建修订」能在 JVM 上逐字断言：
 * DAO 只负责记录/insert，判定本身没有 Android 依赖，编辑器与测试用的是同一个函数。
 */
sealed interface PlanWriteDecision {
    /** 计划字段逐字相同 ⇒ 原地写整行、**0 条修订**、边界不动。 */
    data class Unchanged(val effectiveFromMs: Long) : PlanWriteDecision

    /** 内容变化且时间戳严格前进 ⇒ 插旧计划（**1 条修订**）+ 边界前移。 */
    data class Advance(val previousPlan: MedicationPlanRevision, val changedAt: Long) : PlanWriteDecision
}

/**
 * 计划边界判定（§3.2 三分支）。**拒绝，不覆盖**：同毫秒重复写 / 时钟回拨抛
 * [PlanBoundaryNotAdvancedException]，调用方不得替系统时钟撒谎。
 */
fun decidePlanWrite(previous: Medication, next: Medication, changedAt: Long): PlanWriteDecision {
    val previousPlan = previous.planRevision(changedAt)
    val nextPlan = next.planRevision(changedAt).copy(effectiveFromMs = previous.planEffectiveFromMs)
    if (previousPlan == nextPlan) return PlanWriteDecision.Unchanged(previous.planEffectiveFromMs)
    if (changedAt <= previous.planEffectiveFromMs) {
        throw PlanBoundaryNotAdvancedException(
            medicationId = next.id,
            boundaryMs = previous.planEffectiveFromMs,
            requestedMs = changedAt,
        )
    }
    return PlanWriteDecision.Advance(previousPlan = previousPlan, changedAt = changedAt)
}
