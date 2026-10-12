package com.driezy.medlog.feature.medications.reconcile

/**
 * 全停 / 批量误触护栏——纯函数（docs/medication-daily-reconcile.md §7 E17）。
 *
 * 设 `A` = 当前活跃药集合，`n = |A|`，`p = |toPause|`，`c = |变更集|`。需二次确认 ⇔
 * ① `n > 0 且 p == n`（会停用**全部**活跃药）；**或**
 * ② `p ≥ max(3, ⌈n/2⌉)`（批量停用达半数或以上且不少于 3 味）；**或**
 * ③ `c ≥ 5`（一次核对变更 ≥ 5 味）。
 *
 * **不禁止**「全停」（医生可能确有全停医嘱），只要求**一次点击不足以提交**。
 */
internal object MedicationReconcileGuard {

    fun requiresSecondConfirmation(activeCount: Int, toPauseCount: Int, changedCount: Int): Boolean {
        // ① 会停用全部当前活跃药。
        if (activeCount > 0 && toPauseCount >= activeCount) return true
        // ② 批量停用达半数（向上取整）且不少于 3 味。
        val halfCeil = (activeCount + 1) / 2
        if (toPauseCount >= maxOf(3, halfCeil)) return true
        // ③ 绝对下限：一次核对变更 ≥ 5 味。
        return changedCount >= 5
    }
}
