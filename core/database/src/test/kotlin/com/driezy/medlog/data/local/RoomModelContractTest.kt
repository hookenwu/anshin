package com.driezy.medlog.data.local

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
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
        val medication = Medication(name = "Test", doseUnit = "tablet")
        val log = MedicationLog(medicationId = 7L, scheduledTimeMs = 1_000L)
        val record = HealthRecord(type = "WEIGHT", value = 70.0, timestamp = 2_000L)
        val symptom = SymptomLog(symptoms = "头痛")

        assertFalse(medication.isArchived)
        assertEquals(7L, log.medicationId)
        assertEquals("WEIGHT", record.type)
        assertEquals("", symptom.medicationName)
        assertEquals(24, DatabaseSchema.VERSION)
    }

    @Test
    fun `care note defaults to personal observation active and never carries archival or supersede links`() {
        val note = CareNote(careRecipientId = 3L, title = "护士交代", body = "饭后半小时服药")

        assertEquals(CareNoteAttributionType.PERSONAL_OBSERVATION, note.attributionType)
        assertEquals(CareNoteStatus.ACTIVE, note.status)
        assertEquals(null, note.attributionName)
        assertEquals(null, note.attributionAtMs)
        assertEquals(null, note.attributionText)
        assertEquals(null, note.supersededText)
        assertEquals(null, note.supersededAtMs)
        assertEquals(null, note.updatedAtMs)
        // 三态互不重叠；普通列表默认 ACTIVE + QUESTIONABLE，SUPERSEDED 折叠。
        assertEquals(listOf("ACTIVE", "QUESTIONABLE", "SUPERSEDED"), CareNoteStatus.all)
        assertEquals(listOf("QUESTIONABLE", "ACTIVE"), CareNoteStatus.defaultVisible)
        assertEquals(listOf("SUPERSEDED"), CareNoteStatus.folded)
        // 归属四选一，默认最保守；不存在 MEMBER 目标类型。
        assertEquals(
            listOf("CLINICIAN", "CAREGIVER_EXPERIENCE", "PERSONAL_OBSERVATION", "EXTERNAL_MATERIAL"),
            CareNoteAttributionType.all,
        )
        assertEquals(listOf("MEDICATION", "CARE_TASK", "TODO"), CareNoteTargetType.all)
    }

    @Test
    fun `care todo defaults to open with no closed timestamp and no scheduling fields`() {
        val todo = CareTodo(careRecipientId = 3L, title = "让护士看一下压疮风险")

        assertEquals(CareTodoStatus.OPEN, todo.status)
        assertEquals(null, todo.closedAtMs)
        assertEquals(null, todo.dueAtMs)
        assertEquals(null, todo.sourceType)
        assertEquals(null, todo.sourceId)
        assertEquals(null, todo.sourceNote)
        assertEquals(null, todo.resolutionNote)
        // 三态语义互不重叠：DONE 计完成、CANCELLED 不计完成也不计逾期。
        assertEquals(listOf("DONE", "CANCELLED"), CareTodoStatus.closed)
        assertEquals(listOf("OPEN", "DONE", "CANCELLED"), CareTodoStatus.all)
    }

    @Test
    fun `recipient-scoped entities default to unassigned and recipients keep a stable uuid`() {
        val medication = Medication(name = "Test", doseUnit = "tablet")
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
