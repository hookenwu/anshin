package com.driezy.medlog.data.local

import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.model.SymptomLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomModelContractTest {
    @Test
    fun `database module publishes stable persistence entities without application resources`() {
        val medication = Medication(name = "Test", dose = 1.0, doseUnit = "tablet")
        val log = MedicationLog(medicationId = 7L, scheduledTimeMs = 1_000L)
        val record = HealthRecord(type = "WEIGHT", value = 70.0, timestamp = 2_000L)
        val symptom = SymptomLog(symptoms = "头痛")

        assertFalse(medication.isArchived)
        assertEquals(7L, log.medicationId)
        assertEquals("WEIGHT", record.type)
        assertEquals("", symptom.medicationName)
        assertEquals(19, DatabaseSchema.VERSION)
    }

    @Test
    fun `recipient-scoped entities default to unassigned and recipients keep a stable uuid`() {
        val medication = Medication(name = "Test", dose = 1.0, doseUnit = "tablet")
        assertEquals(0L, medication.careRecipientId)
        assertEquals(0L, HealthRecord(type = "WEIGHT", value = 70.0).careRecipientId)
        assertEquals(0L, SymptomLog().careRecipientId)

        val dad = CareRecipient(displayName = "爸爸")
        val mom = CareRecipient(displayName = "妈妈")
        assertTrue(dad.uuid.isNotBlank())
        assertNotEquals("两位成员必须有不同的稳定标识", dad.uuid, mom.uuid)

        val renamed = dad.copy(displayName = "老爸")
        assertEquals("改名不得影响稳定标识", dad.uuid, renamed.uuid)
        assertEquals(dad.id, renamed.id)
    }
}
