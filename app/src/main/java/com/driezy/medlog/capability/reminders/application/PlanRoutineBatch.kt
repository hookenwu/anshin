package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.IllegalTimeOffsetException
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.TimePeriods
import com.driezy.medlog.data.model.gatedFor
import com.driezy.medlog.data.model.toAnchorOrNull
import java.time.LocalTime

/**
 * 一次作息重算的结算结果：`advanced` = 真的前进了边界的药（各 1 条修订），
 * `noOp` = 内容没变、走了原地写分支的药（0 条修订）。
 */
data class PlanRoutineResult(val advanced: Int = 0, val noOp: Int = 0)

/**
 * 作息重算的**唯一**批量计划器（docs/medication-time-offset.md §3.5，PLAN §4.2）。
 *
 * 纯函数：不吃时钟、不碰事务、不直接写库——`snapshot` 与 `write` 都由调用方在**同一个事务**里给出，
 * 因此「判定集合 ≡ 写入集合」是这一个读视图里同一批行，W4（预检与重算之间插入的冲突药）不可能漏判。
 *
 * 逐药三步：
 * 1. **适格性**：PRN / 间隔给药 / 没有可解析基线的药直接跳过（它们的钟点不是派生值，偏移恒 0）；
 * 2. **[gatedFor]**：写入口闸门（适格性 + 合法性一次判完，适格行内部委托唯一的物化点
 *    `materializedFor`），非法 ⇒ 收集进 `conflicts` 并继续，**不写这一行**；
 * 3. **偏移感知的"未变"判定**：[storedTimesEqual] 拿**物化结果**与库里三列比，而不是拿
 *    `resolve(anchor)` 的基线比——后者会让任何 `offsetMinutes != 0` 的药永远被判成"未变"。
 *
 * 失败语义：
 * - 任一药被 [IllegalTimeOffsetException] 拒 ⇒ 循环跑完（好让对话框一次列全）后抛
 *   [RoutineResyncConflictException]；抛出点在调用方的事务内 ⇒ **整批回滚、零部分写入**。
 * - 其它异常（含 `PlanBoundaryNotAdvancedException`）**原样穿出**，同样整批回滚；本函数不吞任何异常。
 */
suspend fun planRoutineBatch(
    snapshot: List<Medication>,
    schedule: RoutineSchedule,
    changedAt: Long,
    zeroOffsetFor: Set<Long> = emptySet(),
    write: suspend (Medication) -> PlanWriteResult,
): PlanRoutineResult {
    val conflicts = mutableListOf<OffsetConflict>()
    var advanced = 0
    var noOp = 0
    for (med in snapshot) {
        if (!med.isRoutineAnchored()) continue
        val materialized = try {
            med.gatedFor(schedule, offsetOverride = if (med.id in zeroOffsetFor) 0 else null)
        } catch (rejected: IllegalTimeOffsetException) {
            conflicts += rejected.toConflict(med)
            continue
        }
        if (med.storedTimesEqual(materialized)) {
            noOp++
            continue
        }
        when (write(materialized)) {
            PlanWriteResult.Advanced -> advanced++
            PlanWriteResult.NoOp -> noOp++
        }
    }
    if (conflicts.isNotEmpty()) throw RoutineResyncConflictException(conflicts)
    return PlanRoutineResult(advanced = advanced, noOp = noOp)
}

/** 作息锚点药：非 PRN、非间隔给药、至少一个时段能映射到 [com.driezy.medlog.domain.model.RoutineAnchor]。 */
fun Medication.isRoutineAnchored(): Boolean {
    if (isPRN || intervalHours > 0) return false
    return TimePeriods.parse(timePeriod).any { it.toAnchorOrNull() != null }
}

/**
 * 偏移感知的"未变"判定（§5.0.1）：比较 [Medication.reminderTimes]（规范化后）与
 * `reminderHour/reminderMinute`。
 *
 * 只读钟点三列，**不读** `offsetMinutes`（INV-3：没有任何读路径消费偏移）——偏移体现在它算出的钟点上，
 * 钟点没变就是真的没变。`reminderTimes` 先解析再排序去重，历史遗留的乱序串不会造成假"已变"。
 */
fun Medication.storedTimesEqual(other: Medication): Boolean =
    normalizedReminderTimes() == other.normalizedReminderTimes() &&
        reminderHour == other.reminderHour &&
        reminderMinute == other.reminderMinute

private fun Medication.normalizedReminderTimes(): List<LocalTime> = reminderTimes
    .split(',')
    .mapNotNull { raw -> runCatching { LocalTime.parse(raw.trim()) }.getOrNull() }
    .distinct()
    .sorted()
