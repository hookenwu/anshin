package com.driezy.medlog.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 计划边界判定的纯函数（docs/medication-time-offset.md §3.2）：编辑器保存的「一条修订」与
 * 「零修订」在 JVM 上就能断言，DAO 只是把这个决定落库。
 */
class PlanWriteDecisionTest {

    @Test
    fun `a changed plan advances the boundary and yields exactly one revision`() {
        val previous = medication(id = 1L, times = "08:15", offset = 0, effectiveFrom = 1_000L)
        val next = previous.copy(reminderTimes = "08:30", offsetMinutes = 15)

        val decision = decidePlanWrite(previous, next, changedAt = 2_000L)

        assertTrue("必须是 Advance，实际：$decision", decision is PlanWriteDecision.Advance)
        decision as PlanWriteDecision.Advance
        assertEquals("旧计划（快照）就是那一条修订", previous.planRevision(2_000L), decision.previousPlan)
        assertEquals(2_000L, decision.changedAt)
    }

    @Test
    fun `an unchanged save yields no revision and keeps the boundary`() {
        val previous = medication(id = 2L, times = "08:15", offset = 0, effectiveFrom = 1_000L)
        val next = previous.copy(notes = "只改了备注")

        val decision = decidePlanWrite(previous, next, changedAt = 2_000L)

        assertEquals(PlanWriteDecision.Unchanged(1_000L), decision)
    }

    @Test
    fun `a duplicate-timestamp write is rejected with zero writes`() {
        val previous = medication(id = 3L, times = "08:15", offset = 0, effectiveFrom = 2_000L)
        val next = previous.copy(reminderTimes = "08:30", offsetMinutes = 15)

        try {
            decidePlanWrite(previous, next, changedAt = 2_000L)
            fail("同毫秒重复写必须拒绝")
        } catch (error: PlanBoundaryNotAdvancedException) {
            assertEquals(3L, error.medicationId)
            assertEquals(2_000L, error.boundaryMs)
        }
    }

    private fun medication(id: Long, times: String, offset: Int, effectiveFrom: Long) = Medication(
        id = id,
        name = "Medication $id",
        doseUnit = "tablet",
        timePeriod = "afterBreakfast",
        reminderTimes = times,
        reminderHour = times.substringBefore(':').toInt(),
        reminderMinute = times.substringAfter(':').toInt(),
        offsetMinutes = offset,
        planEffectiveFromMs = effectiveFrom,
    )
}
