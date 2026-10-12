package com.driezy.medlog.feature.medications.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯差异计算（T1）——docs/medication-daily-reconcile.md §4.1 / §2 D3 / §9 C5/C6/C7/E6/E7。
 *
 * 判据 = **意图布尔 vs 当前 active**，**不是**「勾选→RESUME / 不勾选→PAUSE」。
 * 「当前 active」由 `MedicationStatusDerivation.statusAt == ACTIVE` 给（UNKNOWN 与 PAUSED 都算非 active）。
 */
class MedicationReconcileDiffTest {

    private fun row(id: Long, active: Boolean, taking: Boolean) = ReconcileRow(id, active, taking)

    private fun diffOf(vararg rows: ReconcileRow) = diff(rows.toList())

    @Test
    fun `checking an active medication is a no-op - never a resume`() {
        // 在服药被勾选：意图 == 当前态 ⇒ 零变化（抓住「勾选→RESUME」的假变更，C5/C6）。
        val d = diffOf(row(1, active = true, taking = true))
        assertTrue(d.toPause.isEmpty())
        assertTrue(d.toResume.isEmpty())
        assertEquals(0, d.changedCount)
    }

    @Test
    fun `unchecking an active medication pauses it`() {
        val d = diffOf(row(1, active = true, taking = false))
        assertEquals(listOf(1L), d.toPause)
        assertTrue(d.toResume.isEmpty())
    }

    @Test
    fun `leaving a paused medication unchecked is a no-op`() {
        val d = diffOf(row(1, active = false, taking = false))
        assertTrue(d.toPause.isEmpty())
        assertTrue(d.toResume.isEmpty())
    }

    @Test
    fun `checking a paused medication resumes it`() {
        val d = diffOf(row(1, active = false, taking = true))
        assertTrue(d.toPause.isEmpty())
        assertEquals(listOf(1L), d.toResume)
    }

    @Test
    fun `a legacy baseline medication is currently paused so checking it is a resume`() {
        // 遗留药当前态 = PAUSED（§7 E16）：不勾 = 无变化；勾 = 显式恢复。
        val unchecked = diffOf(row(1, active = false, taking = false))
        assertTrue(unchecked.changedCount == 0)
        val checked = diffOf(row(1, active = false, taking = true))
        assertEquals(listOf(1L), checked.toResume)
    }

    @Test
    fun `unknown counts as not active so checking it is a resume`() {
        // UNKNOWN（迁移地平线前）与 PAUSED 都算非 active（§4.1）。
        val d = diffOf(row(7, active = false, taking = true))
        assertEquals(listOf(7L), d.toResume)
    }

    @Test
    fun `C7 a mixed reconcile yields exactly the changed medications`() {
        val d = diffOf(
            row(1, active = true, taking = false), // 在服取消勾选 ⇒ PAUSE
            row(2, active = true, taking = false), // 在服取消勾选 ⇒ PAUSE
            row(3, active = false, taking = true), // 停滞勾选 ⇒ RESUME
            row(4, active = true, taking = true), // 不动 ⇒ 无变化
        )
        assertEquals(listOf(1L, 2L), d.toPause)
        assertEquals(listOf(3L), d.toResume)
        assertEquals(3, d.changedCount)
    }

    @Test
    fun `E6 all unchecked pauses every currently active medication`() {
        val d = diffOf(
            row(1, active = true, taking = false),
            row(2, active = true, taking = false),
            row(3, active = false, taking = false),
        )
        assertEquals(listOf(1L, 2L), d.toPause)
        assertTrue(d.toResume.isEmpty())
    }

    @Test
    fun `E7 all checked resumes every non active medication`() {
        val d = diffOf(
            row(1, active = true, taking = true),
            row(2, active = false, taking = true),
            row(3, active = false, taking = true),
        )
        assertTrue(d.toPause.isEmpty())
        assertEquals(listOf(2L, 3L), d.toResume)
    }

    @Test
    fun `an empty roster has no changes`() {
        assertEquals(0, diffOf().changedCount)
    }
}
