package com.driezy.medlog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 间隔派生纯函数（docs/tracked-events-spec.md §4 D4 / §5 R4）。
 *
 * 覆盖点：无锚点、阈值边界、**绝对时长**（与本地日界/DST 无关）、时钟前拨/回拨、取整天数。
 * 纯 JVM，随 `:app:testDebugUnitTest` 执行。
 */
class CareEventIntervalTest {

    private val day = CareEventInterval.MILLIS_PER_DAY

    @Test
    fun `no anchor is never overdue and has no elapsed`() {
        assertNull(CareEventInterval.elapsedMs(null, NOW))
        assertNull(CareEventInterval.wholeDaysSince(null, NOW))
        assertFalse(CareEventInterval.isOverdue(null, NOW, thresholdDays = 1))
    }

    @Test
    fun `below threshold is not overdue but exactly at threshold is`() {
        val anchor = NOW - (2 * day + 23 * 3_600_000L)
        assertFalse(CareEventInterval.isOverdue(anchor, NOW, thresholdDays = 3))

        val exactlyThreeDays = NOW - 3 * day
        assertTrue("now - anchor == 阈值即算超期", CareEventInterval.isOverdue(exactlyThreeDays, NOW, thresholdDays = 3))
    }

    @Test
    fun `elapsed is absolute duration and ignores local day boundaries`() {
        // 锚点 23:30、次日 00:30：本地「日」只差 1，但绝对时长是 1 小时。
        val anchor = NOW - 3_600_000L
        assertEquals(3_600_000L, CareEventInterval.elapsedMs(anchor, NOW))
        assertFalse(
            "不足一天不得因跨本地日而误判超期",
            CareEventInterval.isOverdue(anchor, NOW, thresholdDays = 1),
        )
    }

    @Test
    fun `dst transition does not change absolute elapsed`() {
        // 与 zone 无关：同一批 epoch 毫秒在任何时区/DST 切换下 elapsed 相同。
        val anchor = 1_772_000_000_000L
        val now = anchor + 86_400_000L
        assertEquals(86_400_000L, CareEventInterval.elapsedMs(anchor, now))
        assertTrue(CareEventInterval.isOverdue(anchor, now, thresholdDays = 1))
    }

    @Test
    fun `clock forward past threshold makes it overdue`() {
        val anchor = NOW
        val advanced = NOW + 4 * day
        assertTrue(CareEventInterval.isOverdue(anchor, advanced, thresholdDays = 3))
    }

    @Test
    fun `clock rollback is clamped to zero and never negative`() {
        val anchor = NOW
        val rewound = NOW - 5 * day
        assertEquals(0L, CareEventInterval.elapsedMs(anchor, rewound))
        assertFalse(CareEventInterval.isOverdue(anchor, rewound, thresholdDays = 1))
    }

    @Test
    fun `wholeDaysSince floors toward zero`() {
        assertEquals(2L, CareEventInterval.wholeDaysSince(NOW - (2 * day + 5_000L), NOW))
        assertEquals(0L, CareEventInterval.wholeDaysSince(NOW - 5_000L, NOW))
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
    }
}
