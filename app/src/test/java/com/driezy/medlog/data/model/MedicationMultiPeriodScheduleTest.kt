package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.MedicationSchedule
import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** 一个药品挂多个用餐时段时的排期归约：展开成按钟点升序的多个提醒槽位。 */
class MedicationMultiPeriodScheduleTest {

    private fun medication(timePeriod: String, reminderTimes: String) = Medication(
        id = 1L,
        name = "多时段药",
        dose = 1.0,
        doseUnit = "片",
        timePeriod = timePeriod,
        reminderTimes = reminderTimes,
    )

    @Test
    fun `multiple meal periods become sorted exact slots`() {
        val medication = medication(
            timePeriod = "afterDinner,afterBreakfast",
            reminderTimes = "18:30,08:30",
        )

        val schedule = medication.toDomainSchedule()

        assertTrue("多时段应展开为多槽位计划", schedule is MedicationSchedule.ExactTimes)
        assertEquals(
            listOf(LocalTime.of(8, 30), LocalTime.of(18, 30)),
            (schedule as MedicationSchedule.ExactTimes).times,
        )
    }

    @Test
    fun `single meal period keeps the routine anchor behaviour`() {
        val medication = medication(timePeriod = "afterBreakfast", reminderTimes = "08:30")

        val schedule = medication.toDomainSchedule()

        assertTrue(schedule is MedicationSchedule.RoutineAnchored)
        assertEquals(
            RoutineAnchor.AFTER_BREAKFAST,
            (schedule as MedicationSchedule.RoutineAnchored).anchor,
        )
        assertEquals(LocalTime.of(8, 30), schedule.resolvedTime)
    }

    @Test
    fun `earliest scheduled time of a multi period plan is the first slot`() {
        val medication = medication(
            timePeriod = "afterDinner,afterBreakfast",
            reminderTimes = "18:30,08:30",
        )

        assertEquals(LocalTime.of(8, 30), medication.earliestScheduledTime())
        assertEquals(LocalTime.of(18, 30), medication.scheduledLocalTimeForSlot(1))
    }

    @Test
    fun `unknown or exact encodings fall back to exact times`() {
        val medication = medication(timePeriod = "nonsense", reminderTimes = "07:00,21:00")

        val schedule = medication.toDomainSchedule()

        assertTrue(schedule is MedicationSchedule.ExactTimes)
        assertEquals(
            listOf(LocalTime.of(7, 0), LocalTime.of(21, 0)),
            (schedule as MedicationSchedule.ExactTimes).times,
        )
    }
}
