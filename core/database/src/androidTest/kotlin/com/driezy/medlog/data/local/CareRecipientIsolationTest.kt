package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.model.SymptomLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 0 隔离性验收：同一设备上的两位成员，数据必须互不可见、互不影响。
 * 日志不冗余 careRecipientId，因此按成员过滤依赖 medicationId 归属。
 */
@RunWith(AndroidJUnit4::class)
class CareRecipientIsolationTest {

    private lateinit var db: MedLogDatabase
    private lateinit var recipientDao: CareRecipientDao
    private lateinit var medicationDao: MedicationDao
    private lateinit var logDao: MedicationLogDao
    private lateinit var healthDao: HealthRecordDao
    private lateinit var symptomDao: SymptomLogDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, MedLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recipientDao = db.careRecipientDao()
        medicationDao = db.medicationDao()
        logDao = db.medicationLogDao()
        healthDao = db.healthRecordDao()
        symptomDao = db.symptomLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun medicationsAreScopedPerRecipient() = runBlocking {
        val dad = recipientDao.insert(CareRecipient(displayName = "爸爸"))
        val mom = recipientDao.insert(CareRecipient(displayName = "妈妈"))
        medicationDao.insertMedication(medication(dad, "阿司匹林"))
        medicationDao.insertMedication(medication(mom, "二甲双胍"))

        assertEquals(listOf("阿司匹林"), medicationDao.getActiveMedications(dad).first().map { it.name })
        assertEquals(listOf("二甲双胍"), medicationDao.getActiveMedications(mom).first().map { it.name })
        assertEquals(1, medicationDao.getAllMedicationsOnce(dad).size)
    }

    @Test
    fun logsAndAdherenceCountsFollowMedicationOwner() = runBlocking {
        val dad = recipientDao.insert(CareRecipient(displayName = "爸爸"))
        val mom = recipientDao.insert(CareRecipient(displayName = "妈妈"))
        val dadMed = medicationDao.insertMedication(medication(dad, "阿司匹林"))
        val momMed = medicationDao.insertMedication(medication(mom, "二甲双胍"))

        val dayStart = 1_790_000_000_000L
        logDao.insertLog(
            MedicationLog(
                medicationId = dadMed,
                scheduledTimeMs = dayStart + 3_600_000,
                status = LogStatus.TAKEN,
                stockDeducted = 1.0,
            ),
        )
        logDao.insertLog(
            MedicationLog(
                medicationId = momMed,
                scheduledTimeMs = dayStart + 3_600_000,
                status = LogStatus.SKIPPED,
            ),
        )

        val rangeEnd = dayStart + 86_400_000L
        val dadLogs = logDao.getLogsForDateRange(dad, dayStart, rangeEnd).first()
        val momLogs = logDao.getLogsForDateRange(mom, dayStart, rangeEnd).first()

        assertEquals(1, dadLogs.size)
        assertEquals(LogStatus.TAKEN, dadLogs.single().status)
        assertEquals(LogStatus.SKIPPED, momLogs.single().status)
        assertEquals(1, logDao.getTakenCountForDateRange(dad, dayStart, rangeEnd).first())
        assertEquals(0, logDao.getTakenCountForDateRange(mom, dayStart, rangeEnd).first())
        assertEquals(1, logDao.getLogsForRangeOnce(dad, dayStart, rangeEnd).size)

        // 计划版本同样按 medicationId 继承成员
        assertEquals(0, medicationDao.observePlanRevisions(dad).first().size)
    }

    @Test
    fun sameSourceCacheKeyIsAllowedForTwoRecipients() = runBlocking {
        val dad = recipientDao.insert(CareRecipient(displayName = "爸爸"))
        val mom = recipientDao.insert(CareRecipient(displayName = "妈妈"))
        val cacheKey = "cloud:bp:2026-10-03"

        val dadRow = healthDao.insert(healthRecord(dad, cacheKey))
        val momRow = healthDao.insert(healthRecord(mom, cacheKey))

        assertTrue("两位成员可以各自导入同一份报告", dadRow > 0 && momRow > 0)
        assertEquals(1, healthDao.getAllRecords(dad).first().size)
        assertTrue(healthDao.hasSourceCacheKey(dad, cacheKey))
        assertFalse(healthDao.hasSourceCacheKey(mom, "cloud:bp:other"))

        // 同一成员重复导入同一份缓存 key 仍被忽略
        assertEquals(-1L, healthDao.insert(healthRecord(dad, cacheKey)))
        assertEquals(1, healthDao.getAllRecords(dad).first().size)
    }

    @Test
    fun deletingRecipientCascadesOwnDataAndLeavesOtherRecipientIntact() = runBlocking {
        val dad = recipientDao.insert(CareRecipient(displayName = "爸爸"))
        val mom = recipientDao.insert(CareRecipient(displayName = "妈妈"))
        val dadMed = medicationDao.insertMedication(medication(dad, "阿司匹林"))
        val momMed = medicationDao.insertMedication(medication(mom, "二甲双胍"))

        logDao.insertLog(MedicationLog(medicationId = dadMed, scheduledTimeMs = 1_790_000_000_000L))
        logDao.insertLog(MedicationLog(medicationId = momMed, scheduledTimeMs = 1_790_000_000_000L))
        healthDao.insert(healthRecord(dad, "cloud:bp:a"))
        healthDao.insert(healthRecord(mom, "cloud:bp:b"))
        symptomDao.insert(SymptomLog(careRecipientId = dad, symptoms = "头痛"))
        symptomDao.insert(SymptomLog(careRecipientId = mom, symptoms = "乏力"))

        recipientDao.deleteById(dad)

        assertNull(recipientDao.getById(dad))
        assertNotNull(recipientDao.getById(mom))
        assertTrue(medicationDao.getAllMedicationsOnce(dad).isEmpty())
        assertEquals(1, medicationDao.getAllMedicationsOnce(mom).size)
        assertEquals(0, logDao.getLogsForRangeOnce(dad, 0, Long.MAX_VALUE).size)
        assertEquals(1, logDao.getLogsForRangeOnce(mom, 0, Long.MAX_VALUE).size)
        assertEquals(0, healthDao.getAllRecords(dad).first().size)
        assertEquals(1, healthDao.getAllRecords(mom).first().size)
        assertEquals(0, symptomDao.getAllLogs(dad).first().size)
        assertEquals(1, symptomDao.getAllLogs(mom).first().size)
    }

    private fun medication(recipientId: Long, name: String) = Medication(
        careRecipientId = recipientId,
        name = name,
        doseUnit = "片",
    )

    private fun healthRecord(recipientId: Long, sourceCacheKey: String) = HealthRecord(
        careRecipientId = recipientId,
        type = "BLOOD_PRESSURE",
        value = 120.0,
        secondaryValue = 80.0,
        sourceCacheKey = sourceCacheKey,
    )
}
