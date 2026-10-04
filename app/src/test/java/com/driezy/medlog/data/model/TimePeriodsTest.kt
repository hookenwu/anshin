package com.driezy.medlog.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimePeriodsTest {

    @Test
    fun `single legacy key stays valid`() {
        assertEquals(listOf(TimePeriod.AFTER_BREAKFAST), TimePeriods.parse("afterBreakfast"))
    }

    @Test
    fun `multiple keys are decoded in declaration order`() {
        val parsed = TimePeriods.parse("afterDinner,beforeBreakfast")

        assertEquals(listOf(TimePeriod.BEFORE_BREAKFAST, TimePeriod.AFTER_DINNER).sortedBy { it.ordinal }, parsed)
        assertEquals(listOf(TimePeriod.AFTER_DINNER, TimePeriod.BEFORE_BREAKFAST), parsed)
    }

    @Test
    fun `duplicates unknown keys and blanks are dropped`() {
        assertEquals(
            listOf(TimePeriod.BEDTIME),
            TimePeriods.parse(" bedtime , , bedtime ,nonsense, "),
        )
    }

    @Test
    fun `exact means no routine anchor`() {
        assertTrue(TimePeriods.isExact("exact"))
        assertTrue(TimePeriods.isExact(""))
        assertTrue(TimePeriods.isExact(null))
        assertFalse(TimePeriods.isExact("afterLunch"))
    }

    @Test
    fun `an exact entry mixed with real periods is dropped instead of wiping them`() {
        assertEquals(listOf(TimePeriod.AFTER_LUNCH), TimePeriods.parse("exact,afterLunch"))
        assertFalse(TimePeriods.isExact("exact,afterLunch"))
    }

    @Test
    fun `encode round trips and falls back to exact for an empty set`() {
        val encoded = TimePeriods.encode(listOf(TimePeriod.AFTER_BREAKFAST, TimePeriod.AFTER_DINNER))

        assertEquals("afterBreakfast,afterDinner", encoded)
        assertEquals(
            listOf(TimePeriod.AFTER_BREAKFAST, TimePeriod.AFTER_DINNER),
            TimePeriods.parse(encoded),
        )
        assertEquals("exact", TimePeriods.encode(emptyList()))
        assertEquals("exact", TimePeriods.encode(listOf(TimePeriod.EXACT)))
    }

    @Test
    fun `contains answers per period membership`() {
        assertTrue(TimePeriods.contains("afterBreakfast,afterDinner", TimePeriod.AFTER_DINNER))
        assertFalse(TimePeriods.contains("afterBreakfast", TimePeriod.AFTER_DINNER))
    }
}
