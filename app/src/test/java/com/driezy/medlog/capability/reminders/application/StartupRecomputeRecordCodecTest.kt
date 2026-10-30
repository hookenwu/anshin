package com.driezy.medlog.capability.reminders.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久标记的编码/解码（docs/medication-time-offset.md §3.6）：DataStore 里存的是这组稳定键，
 * 因此"下一次启动 / UI 读到的到底是什么"可以在 JVM 上逐字钉住，不需要真 DataStore。
 */
class StartupRecomputeRecordCodecTest {

    @Test
    fun `a converged record round-trips with its counts`() {
        val record = StartupRecomputeRecord(
            status = StartupRecomputeStatus.CONVERGED,
            recipientId = 7L,
            advanced = 3,
            noOp = 2,
            atMs = 1_700_000_000_123L,
        )

        val decoded = record.encodeStartupRecompute().decodeStartupRecompute()

        assertEquals(record, decoded)
        assertTrue(decoded!!.converged)
        assertNull(decoded.failure)
        assertTrue(decoded.conflicts.isEmpty())
    }

    @Test
    fun `a failed record keeps its class, conflict lines, detail and attempts`() {
        val record = StartupRecomputeRecord(
            status = StartupRecomputeStatus.FAILED,
            recipientId = 7L,
            failure = StartupRecomputeFailure.ILLEGAL_OFFSET,
            advanced = 0,
            noOp = 1,
            conflicts = listOf(
                "id=2 name=Medication 2 anchor=BEDTIME offset=30min " +
                    "base=22:30 -> minuteOfDay=1440 reason=CROSS_MIDNIGHT",
                "id=5 name=Medication 5 anchor=BREAKFAST offset=60min base=08:00 -> minuteOfDay=540 reason=REWIND",
            ),
            detail = "RoutineResyncConflictException: rejected",
            atMs = 1_700_000_000_123L,
            attempts = 4,
        )

        val decoded = record.encodeStartupRecompute().decodeStartupRecompute()

        assertEquals(record, decoded)
        assertTrue("多行冲突必须逐行还原（分隔符不能与内容冲突）", decoded!!.conflicts == record.conflicts)
        assertEquals(StartupRecomputeFailure.ILLEGAL_OFFSET, decoded.failure)
        assertEquals(4, decoded.attempts)
    }

    @Test
    fun `bad or missing keys decode to null so the next start simply recomputes`() {
        assertNull(emptyMap<String, String>().decodeStartupRecompute())
        assertNull(mapOf("status" to "NOT_A_STATUS", "recipient_id" to "7").decodeStartupRecompute())
        assertNull("缺 recipient 的记录不可信", mapOf("status" to "CONVERGED").decodeStartupRecompute())
    }

    @Test
    fun `a record with no optional fields still decodes`() {
        val decoded = mapOf("status" to "SKIPPED_NO_RECIPIENT", "recipient_id" to "0").decodeStartupRecompute()

        assertEquals(StartupRecomputeStatus.SKIPPED_NO_RECIPIENT, decoded?.status)
        assertEquals(0L, decoded?.recipientId)
        assertNull(decoded?.detail)
        assertEquals(0, decoded?.attempts)
    }
}
