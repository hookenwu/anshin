package com.driezy.medlog.feature.medications.list

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.earliestScheduledTime
import com.driezy.medlog.data.repository.MedicationSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalTime

class MedicationSortTest {

    private fun med(
        id: Long,
        name: String,
        reminderTimes: String = "08:00",
        timePeriod: String = "exact",
        reminderHour: Int = 8,
        reminderMinute: Int = 0,
        intervalHours: Int = 0,
        isPRN: Boolean = false,
    ) = Medication(
        id = id,
        name = name,
        doseUnit = "片",
        reminderTimes = reminderTimes,
        timePeriod = timePeriod,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        intervalHours = intervalHours,
        isPRN = isPRN,
    )

    @Test
    fun `earliest scheduled time of multiple exact times is the earliest one`() {
        val medication = med(1, "A", reminderTimes = "20:30,08:00,12:15")

        assertEquals(LocalTime.of(8, 0), medication.earliestScheduledTime())
    }

    @Test
    fun `routine anchored medication uses its resolved clock time`() {
        val medication = med(1, "A", reminderTimes = "08:30", timePeriod = "afterBreakfast")

        assertEquals(LocalTime.of(8, 30), medication.earliestScheduledTime())
    }

    @Test
    fun `interval dosing falls back to the main reminder time`() {
        val medication = med(1, "A", reminderHour = 7, reminderMinute = 30, intervalHours = 8)

        assertEquals(LocalTime.of(7, 30), medication.earliestScheduledTime())
    }

    @Test
    fun `as needed medication falls back to the main reminder time`() {
        val medication = med(1, "A", reminderHour = 9, reminderMinute = 15, isPRN = true)

        assertEquals(LocalTime.of(9, 15), medication.earliestScheduledTime())
    }

    @Test
    fun `time ascending sorts by earliest dose of the day`() {
        val evening = med(1, "晚间", reminderTimes = "20:00")
        val morning = med(2, "早间", reminderTimes = "07:00")
        val noon = med(3, "午间", reminderTimes = "12:00")

        val sorted = listOf(evening, morning, noon).sortedFor(MedicationSortOrder.TIME_ASC)

        assertEquals(listOf("早间", "午间", "晚间"), sorted.map { it.name })
    }

    @Test
    fun `time descending sorts by latest dose of the day first`() {
        val evening = med(1, "晚间", reminderTimes = "20:00")
        val morning = med(2, "早间", reminderTimes = "07:00")
        val noon = med(3, "午间", reminderTimes = "12:00")

        val sorted = listOf(evening, morning, noon).sortedFor(MedicationSortOrder.TIME_DESC)

        assertEquals(listOf("晚间", "午间", "早间"), sorted.map { it.name })
    }

    @Test
    fun `equal times keep a stable name order in both directions`() {
        val b = med(1, "B 药", reminderTimes = "08:00")
        val a = med(2, "A 药", reminderTimes = "08:00")

        assertEquals(listOf("A 药", "B 药"), listOf(b, a).sortedFor(MedicationSortOrder.TIME_ASC).map { it.name })
        assertEquals(listOf("A 药", "B 药"), listOf(b, a).sortedFor(MedicationSortOrder.TIME_DESC).map { it.name })
    }

    @Test
    fun `default order keeps the dao order untouched`() {
        val first = med(1, "乙", reminderTimes = "20:00")
        val second = med(2, "甲", reminderTimes = "07:00")
        val input = listOf(first, second)

        val sorted = input.sortedFor(MedicationSortOrder.DEFAULT)

        assertSame(input, sorted)
        assertEquals(listOf("乙", "甲"), sorted.map { it.name })
    }

    @Test
    fun `stored order name falls back to default when unknown`() {
        assertEquals(MedicationSortOrder.TIME_ASC, MedicationSortOrder.fromStoredName("TIME_ASC"))
        assertEquals(MedicationSortOrder.DEFAULT, MedicationSortOrder.fromStoredName("nonsense"))
        assertEquals(MedicationSortOrder.DEFAULT, MedicationSortOrder.fromStoredName(null))
    }
}
