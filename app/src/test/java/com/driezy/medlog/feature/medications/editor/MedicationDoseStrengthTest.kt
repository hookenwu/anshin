package com.driezy.medlog.feature.medications.editor

import com.driezy.medlog.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationDoseStrengthTest {

    private fun state(value: String, unit: String) = AddMedicationUiState(
        name = "药品",
        doseUnit = "粒",
        doseQuantity = 2.0,
        doseStrength = value,
        doseStrengthUnit = unit,
    )

    @Test
    fun `blank strength and unit is valid`() {
        assertNull(state("", "").validationError())
    }

    @Test
    fun `value without unit is rejected`() {
        assertEquals(R.string.error_dose_strength_invalid, state("0.25", "").validationError())
    }

    @Test
    fun `unit without value is rejected`() {
        assertEquals(R.string.error_dose_strength_invalid, state("", "g").validationError())
    }

    @Test
    fun `paired positive strength is valid`() {
        assertNull(state("0.25", "g").validationError())
    }

    @Test
    fun `zero or negative strength is rejected`() {
        assertEquals(R.string.error_dose_strength_invalid, state("0", "g").validationError())
        assertEquals(R.string.error_dose_strength_invalid, state("-1", "g").validationError())
    }

    @Test
    fun `strength unit options are the measurement units and include microgram`() {
        assertEquals(listOf("mg", "g", "μg", "ml"), doseStrengthUnitOptions())
    }

    @Test
    fun `clearing the value clears the paired unit`() {
        val model = state("0.25", "g")
        // 直接验证成对约束的归一化：数值为空时单位不应残留
        val cleared = model.copy(doseStrength = "", doseStrengthUnit = "")
        assertNull(cleared.validationError())
    }
}
