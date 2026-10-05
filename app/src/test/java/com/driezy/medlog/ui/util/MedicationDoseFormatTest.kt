package com.driezy.medlog.ui.util

import com.driezy.medlog.data.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationDoseFormatTest {

    private fun med(
        doseQuantity: Double = 2.0,
        doseUnit: String = "粒",
        doseStrength: Double? = null,
        doseStrengthUnit: String? = null,
    ) = Medication(
        name = "测试药",
        doseUnit = doseUnit,
        doseQuantity = doseQuantity,
        doseStrength = doseStrength,
        doseStrengthUnit = doseStrengthUnit,
    )

    @Test
    fun `with strength shows unit strength times count`() {
        val text = med(doseStrength = 0.25, doseStrengthUnit = "g").doseDisplayText()
        assertEquals("0.25g × 2粒", text)
    }

    @Test
    fun `per dose total multiplies strength by quantity`() {
        assertEquals("0.5g", med(doseStrength = 0.25, doseStrengthUnit = "g").perDoseTotalText())
    }

    @Test
    fun `integer totals omit the decimal point`() {
        val m = med(doseQuantity = 4.0, doseStrength = 0.25, doseStrengthUnit = "g")
        assertEquals("1g", m.perDoseTotalText())
    }

    @Test
    fun `without strength keeps the legacy quantity and unit`() {
        val m = med()
        assertEquals("2 粒", m.doseDisplayText())
        assertNull(m.perDoseTotalText())
    }

    @Test
    fun `countable dose units scale with any measurement strength`() {
        val tablet = med(doseUnit = "片", doseQuantity = 3.0, doseStrength = 0.2, doseStrengthUnit = "mg")
        assertEquals("0.2mg × 3片", tablet.doseDisplayText())
        assertEquals("0.6mg", tablet.perDoseTotalText())
    }

    @Test
    fun `non convertible metric families keep the pair but expose no total`() {
        // 规格是质量（mg），剂量是体积（ml）——不同计量口径，不做乘法求和。
        val m = med(doseUnit = "ml", doseStrength = 0.25, doseStrengthUnit = "mg")
        assertEquals("0.25mg × 2ml", m.doseDisplayText())
        assertNull(m.perDoseTotalText())
    }

    @Test
    fun `strength with blank unit is treated as absent for display`() {
        val m = med(doseStrength = 0.25, doseStrengthUnit = "")
        assertEquals("2 粒", m.doseDisplayText())
        assertNull(m.perDoseTotalText())
    }
}
