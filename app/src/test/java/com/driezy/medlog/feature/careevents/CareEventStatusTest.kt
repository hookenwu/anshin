package com.driezy.medlog.feature.careevents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日页排便状态派生（docs/tracked-events-spec.md §6）。纯 JVM。
 */
class CareEventStatusTest {

    private val day = 86_400_000L
    private val now = 1_780_000_000_000L

    @Test
    fun `no record yields guidance status`() {
        val status = buildCareEventStatus(anchorMs = null, nowMs = now)
        assertFalse(status.hasAnyRecord)
        assertNull(status.daysSince)
    }

    @Test
    fun `days since floors and never goes negative`() {
        assertTrue(buildCareEventStatus(now - 5_000L, now).let { it.hasAnyRecord && it.daysSince == 0L })
        assertEquals(3L, buildCareEventStatus(now - (3 * day + 5_000L), now).daysSince)
        assertEquals(0L, buildCareEventStatus(now + day, now).daysSince)
    }
}
