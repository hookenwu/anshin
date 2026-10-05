package com.driezy.medlog.ui.util

import com.driezy.medlog.data.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Test

class MedicationDisplayNameTest {
    @Test
    fun `display name hides debug seed profile prefix`() {
        val med = Medication(name = "Seed Standard Lisinopril", doseUnit = "mg")

        assertEquals("Lisinopril", med.displayName())
    }

    @Test
    fun `display name keeps normal medication name`() {
        val med = Medication(name = "Metformin XR", doseUnit = "mg")

        assertEquals("Metformin XR", med.displayName())
    }

    @Test
    fun `display name falls back to original when seed prefix has no body`() {
        val med = Medication(name = "Seed Standard", doseUnit = "mg")

        assertEquals("Seed Standard", med.displayName())
    }
}
