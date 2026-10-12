package com.driezy.medlog.feature.medications.reconcile

/**
 * 今日用药核对——纯差异计算（docs/medication-daily-reconcile.md §4.1）。
 *
 * 判据是「**意图布尔 vs 当前 active**」，**不是**「勾选 → RESUME」：一个无事件新药 `live kind = null` 被勾选
 * 若朴素映射成 `RESUME` 会凭空写一条恢复事件（§1.3 陷阱 / §9 C5）。
 */

/** 一行 = 药 id、当前是否 active（`statusAt == ACTIVE`）、复选框意图。 */
internal data class ReconcileRow(val id: Long, val currentlyActive: Boolean, val stillTaking: Boolean)

/** 变更集：停用组 + 恢复组（两组互斥，均只含**真正变化**的药）。 */
internal data class ReconcileDiff(val toPause: List<Long>, val toResume: List<Long>) {
    val changedCount: Int get() = toPause.size + toResume.size
}

/**
 * `currentlyActive` 必须来自 `MedicationStatusDerivation.statusAt(events, now) == ACTIVE`
 * （`UNKNOWN` 与 `PAUSED` 都算「非 active」 ⇒ 预不勾选；勾选它们 ⇒ 走 `toResume`）。
 */
internal fun diff(rows: List<ReconcileRow>): ReconcileDiff = ReconcileDiff(
    toPause = rows.filter { !it.stillTaking && it.currentlyActive }.map { it.id },
    toResume = rows.filter { it.stillTaking && !it.currentlyActive }.map { it.id },
)
