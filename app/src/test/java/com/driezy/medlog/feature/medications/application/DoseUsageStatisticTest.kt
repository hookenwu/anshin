package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import org.junit.Assert.assertEquals
import org.junit.Test

class DoseUsageStatisticTest {

    private val medicationId = 7L

    private fun med(
        doseQuantity: Double = 2.0,
        doseUnit: String = "粒",
        doseStrength: Double? = null,
        doseStrengthUnit: String? = null,
    ) = Medication(
        id = medicationId,
        name = "测试药",
        doseUnit = doseUnit,
        doseQuantity = doseQuantity,
        doseStrength = doseStrength,
        doseStrengthUnit = doseStrengthUnit,
    )

    private fun log(status: LogStatus, actual: Double?, at: Long = 1_700_000_000_000L) = MedicationLog(
        medicationId = medicationId,
        scheduledTimeMs = at,
        status = status,
        actualDoseQuantity = actual,
    )

    @Test
    fun `with strength multiplies taken quantity by unit strength`() {
        val logs = listOf(
            log(LogStatus.TAKEN, 2.0),
            log(LogStatus.TAKEN, 2.0, at = 1_700_000_100_000L),
        )

        val usage = accumulatedDoseUsage(med(doseStrength = 0.25, doseStrengthUnit = "g"), logs)

        assertEquals(1.0, usage.value, 1e-9)
        assertEquals("g", usage.unit)
        assertEquals("1 g", usage.formatted())
    }

    @Test
    fun `taken log without recorded quantity falls back to planned dose`() {
        val logs = listOf(log(LogStatus.TAKEN, null), log(LogStatus.TAKEN, 2.0, at = 1L))

        val usage = accumulatedDoseUsage(med(doseStrength = 0.25, doseStrengthUnit = "g"), logs)

        // 2（回落计划剂量）+ 2 = 4，再乘以规格 0.25 g → 1.0 g
        assertEquals(1.0, usage.value, 1e-9)
        assertEquals("g", usage.unit)
    }

    @Test
    fun `partial and skipped logs are excluded`() {
        val logs = listOf(
            log(LogStatus.TAKEN, 2.0),
            log(LogStatus.PARTIAL, 1.0, at = 2L),
            log(LogStatus.SKIPPED, null, at = 3L),
            log(LogStatus.MISSED, null, at = 4L),
        )

        val usage = accumulatedDoseUsage(med(doseStrength = 0.25, doseStrengthUnit = "g"), logs)

        assertEquals(0.5, usage.value, 1e-9)
    }

    @Test
    fun `without strength degrades to accumulated count in the dose unit`() {
        val logs = listOf(
            log(LogStatus.TAKEN, 2.0),
            log(LogStatus.TAKEN, 2.0, at = 2L),
        )

        val usage = accumulatedDoseUsage(med(), logs)

        assertEquals(4.0, usage.value, 1e-9)
        assertEquals("粒", usage.unit)
        assertEquals("4 粒", usage.formatted())
    }

    @Test
    fun `non convertible units degrade to count instead of a wrong total`() {
        val logs = listOf(log(LogStatus.TAKEN, 2.0))

        // 规格质量单位 mg 配体积剂量单位 ml：不乘规格。
        val usage = accumulatedDoseUsage(med(doseUnit = "ml", doseStrength = 0.25, doseStrengthUnit = "mg"), logs)

        assertEquals(2.0, usage.value, 1e-9)
        assertEquals("ml", usage.unit)
    }

    @Test
    fun `logs of other medications are ignored`() {
        val logs = listOf(
            log(LogStatus.TAKEN, 2.0),
            MedicationLog(medicationId = 99L, scheduledTimeMs = 5L, status = LogStatus.TAKEN, actualDoseQuantity = 5.0),
        )

        val usage = accumulatedDoseUsage(med(doseStrength = 0.25, doseStrengthUnit = "g"), logs)

        assertEquals(0.5, usage.value, 1e-9)
    }
}
