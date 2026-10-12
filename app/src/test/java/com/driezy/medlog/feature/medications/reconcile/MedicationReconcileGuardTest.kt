package com.driezy.medlog.feature.medications.reconcile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全停 / 批量误触护栏（T1）——docs/medication-daily-reconcile.md §7 E17 / §9 C17。
 *
 * 需二次确认 ⇔ ① `n > 0 且 p == n`；② `p ≥ max(3, ⌈n/2⌉)`；③ `c ≥ 5`。
 */
class MedicationReconcileGuardTest {

    @Test
    fun `pausing every active medication requires a second confirmation`() {
        // p == n（n = 6）：最高危单点。
        assertTrue(
            MedicationReconcileGuard.requiresSecondConfirmation(activeCount = 6, toPauseCount = 6, changedCount = 6),
        )
        assertTrue(
            MedicationReconcileGuard.requiresSecondConfirmation(activeCount = 2, toPauseCount = 2, changedCount = 2),
        )
    }

    @Test
    fun `half of the active medications with a floor of three requires a second confirmation`() {
        // p = 3, n = 6 ⇒ ⌈n/2⌉ = 3 ⇒ 触发。
        assertTrue(MedicationReconcileGuard.requiresSecondConfirmation(3, 3, 3))
        // p = 5, n = 10 ⇒ ⌈n/2⌉ = 5 ⇒ 触发。
        assertTrue(MedicationReconcileGuard.requiresSecondConfirmation(10, 5, 5))
        // 下限 3：n = 4, p = 3 ⇒ max(3, 2) = 3 ⇒ 触发（p < n 仍触发）。
        assertTrue(MedicationReconcileGuard.requiresSecondConfirmation(4, 3, 3))
    }

    @Test
    fun `a single pause among six does not require a second confirmation`() {
        assertFalse(MedicationReconcileGuard.requiresSecondConfirmation(6, 1, 1))
    }

    @Test
    fun `below the half threshold and floor does not trigger`() {
        // n = 4, p = 2, c = 2 ⇒ max(3, 2) = 3 ⇒ 不触发；c < 5 ⇒ 不触发。
        assertFalse(MedicationReconcileGuard.requiresSecondConfirmation(4, 2, 2))
    }

    @Test
    fun `five or more changes in one reconcile requires a second confirmation`() {
        assertTrue(
            MedicationReconcileGuard.requiresSecondConfirmation(activeCount = 10, toPauseCount = 0, changedCount = 5),
        )
    }

    @Test
    fun `an empty roster never triggers`() {
        assertFalse(MedicationReconcileGuard.requiresSecondConfirmation(0, 0, 0))
    }
}
