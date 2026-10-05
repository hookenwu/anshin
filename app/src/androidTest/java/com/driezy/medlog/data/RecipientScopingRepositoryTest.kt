package com.driezy.medlog.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.local.MedLogDatabase
import com.driezy.medlog.data.local.RoomTransactionRunner
import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.SymptomLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.data.repository.CareRecipientRepositoryImpl
import com.driezy.medlog.data.repository.HealthRepository
import com.driezy.medlog.data.repository.HealthRepositoryImpl
import com.driezy.medlog.data.repository.LogRepository
import com.driezy.medlog.data.repository.LogRepositoryImpl
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationRepositoryImpl
import com.driezy.medlog.data.repository.SymptomRepository
import com.driezy.medlog.data.repository.SymptomRepositoryImpl
import com.driezy.medlog.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock

/**
 * 阶段 0 仓储层验收：真实 repository + ActiveRecipientStore（DataStore）驱动下的成员隔离。
 *
 * 与 core:database 的 DAO 级隔离测试互补：这里验证写路径自动绑定当前成员、
 * 读取路径随当前成员切换、未选成员时拒绝写入、删除成员级联并切换当前成员。
 */
@RunWith(AndroidJUnit4::class)
class RecipientScopingRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: MedLogDatabase
    private lateinit var activeRecipient: ActiveRecipientStore
    private lateinit var medications: MedicationRepository
    private lateinit var logs: LogRepository
    private lateinit var health: HealthRepository
    private lateinit var symptoms: SymptomRepository
    private lateinit var recipients: CareRecipientRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MedLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val transactions = RoomTransactionRunner(database)
        activeRecipient = ActiveRecipientStore(context)
        medications = MedicationRepositoryImpl(
            database.medicationDao(),
            transactions,
            activeRecipient,
            Clock.systemDefaultZone(),
        )
        logs = LogRepositoryImpl(database.medicationLogDao(), activeRecipient)
        health = HealthRepositoryImpl(database.healthRecordDao(), activeRecipient)
        symptoms = SymptomRepositoryImpl(database.symptomLogDao(), activeRecipient)
        recipients = CareRecipientRepositoryImpl(
            database.careRecipientDao(),
            activeRecipient,
            UserPreferencesRepository(context, activeRecipient),
            transactions,
        )
    }

    @After
    fun tearDown() = runBlocking {
        // 测试会改写真实 DataStore 的当前成员键；归零后应用启动时会自动重新选中第一位成员。
        activeRecipient.set(ActiveRecipientStore.NO_RECIPIENT)
        database.close()
    }

    @Test
    fun medicationsAndHealthRecordsAreIsolatedPerActiveRecipient() = runBlocking {
        val dad = recipients.create("爸爸")
        assertEquals("首次创建自动成为当前成员", dad, activeRecipient.current())

        medications.addMedication(medication("阿司匹林"))
        medications.addMedication(medication("二甲双胍"))
        health.addRecord(HealthRecord(type = "BLOOD_PRESSURE", value = 120.0))
        assertEquals(2, medications.getActiveMedications().first().size)
        assertEquals(1, health.getAllRecords().first().size)

        val mom = recipients.create("妈妈")
        assertEquals("第二位成员不应抢占当前成员", dad, activeRecipient.current())

        recipients.setActiveRecipient(mom)
        assertEquals(mom, activeRecipient.current())
        assertTrue("妈妈的药箱必须为空", medications.getActiveMedications().first().isEmpty())
        assertTrue("妈妈的健康记录必须为空", health.getAllRecords().first().isEmpty())

        medications.addMedication(medication("降压药"))
        assertEquals(listOf("降压药"), medications.getActiveMedications().first().map { it.name })
        assertEquals(0, logs.getLogsForRangeOnce(0, Long.MAX_VALUE).size)

        recipients.setActiveRecipient(dad)
        assertEquals(
            listOf("阿司匹林", "二甲双胍").sorted(),
            medications.getActiveMedications().first().map { it.name }.sorted(),
        )
        assertEquals(1, health.getAllRecords().first().size)
        assertEquals(0, medications.getActiveOnce().count { it.careRecipientId == mom })
    }

    @Test
    fun writesAreRefusedWithoutActiveRecipient() = runBlocking {
        activeRecipient.set(ActiveRecipientStore.NO_RECIPIENT)
        val failure = runCatching { medications.addMedication(medication("孤立药品")) }
        assertTrue("未选成员时必须拒绝写入", failure.isFailure)
        assertEquals(0, database.medicationDao().getAllMedicationsOnce(1L).size)
    }

    @Test
    fun deletingActiveRecipientCascadesAndMovesToRemainingRecipient() = runBlocking {
        val dad = recipients.create("爸爸")
        val mom = recipients.create("妈妈")
        recipients.setActiveRecipient(mom)
        medications.addMedication(medication("妈妈的药"))
        health.addRecord(HealthRecord(type = "WEIGHT", value = 60.0))

        recipients.setActiveRecipient(dad)
        medications.addMedication(medication("爸爸的药"))
        health.addRecord(HealthRecord(type = "WEIGHT", value = 70.0))

        recipients.delete(mom)

        assertEquals("删除当前成员后应切换到剩余成员", dad, activeRecipient.current())
        assertNotNull(recipients.getById(dad))
        assertEquals(listOf("爸爸的药"), medications.getActiveMedications().first().map { it.name })
        assertEquals(1, health.getAllRecords().first().size)
        assertEquals("被删成员的药品必须级联清除", 0, database.medicationDao().getAllMedicationsOnce(mom).size)
        assertEquals("被删成员的健康记录必须级联清除", 0, database.healthRecordDao().getAllRecords(mom).first().size)
        val allRecipients = recipients.getRecipients().map { it.id }
        assertFalse(mom in allRecipients)
        assertNotEquals(mom, dad)

        val uuidBefore = recipients.getById(dad)!!.uuid
        assertTrue(recipients.rename(dad, "老爸"))
        assertEquals(uuidBefore, recipients.getById(dad)!!.uuid)
        assertEquals("老爸", recipients.getById(dad)!!.displayName)
    }

    @Test
    fun editingHealthRecordKeepsOriginalOwnerWhenCallerOmitsRecipient() = runBlocking {
        val dad = recipients.create("爸爸")
        val recordId = health.addRecord(HealthRecord(type = "BLOOD_PRESSURE", value = 120.0))
        assertEquals(1, health.getAllRecords().first().size)

        // 编辑路径重建实体时漏带 careRecipientId（默认 0）：归属必须沿用库中原行，而不是写成 0。
        val rebuilt = HealthRecord(
            id = recordId,
            careRecipientId = 0L,
            type = "BLOOD_PRESSURE",
            value = 135.0,
        )
        health.updateRecord(rebuilt)

        val stored = database.healthRecordDao().getById(recordId)
        assertNotNull(stored)
        assertEquals("编辑后归属必须仍是原成员", dad, stored!!.careRecipientId)
        assertNotEquals("归属不得被写成 0", 0L, stored.careRecipientId)
        assertEquals("编辑后的值必须写入", 135.0, stored.value, 0.0)

        // 仍对原成员可见。
        assertEquals(listOf(recordId), health.getAllRecords().first().map { it.id })

        // 其他成员看不到（行没有移动到任何其他成员名下）。
        val mom = recipients.create("妈妈")
        recipients.setActiveRecipient(mom)
        assertTrue("记录不得移动到其他成员", health.getAllRecords().first().isEmpty())
    }

    @Test
    fun editingSymptomLogKeepsOriginalOwnerWhenCallerOmitsRecipient() = runBlocking {
        val dad = recipients.create("爸爸")
        val logId = symptoms.insert(SymptomLog(symptoms = "头痛", overallRating = 3))
        assertEquals(1, symptoms.getAllLogs().first().size)

        // 与健康记录编辑同源的缺陷路径：重建实体漏带 careRecipientId。
        val rebuilt = SymptomLog(
            id = logId,
            careRecipientId = 0L,
            symptoms = "头痛,恶心",
            overallRating = 2,
        )
        symptoms.update(rebuilt)

        val stored = database.symptomLogDao().getById(logId)
        assertNotNull(stored)
        assertEquals("编辑后归属必须仍是原成员", dad, stored!!.careRecipientId)
        assertNotEquals("归属不得被写成 0", 0L, stored.careRecipientId)
        assertEquals("编辑后的症状必须写入", "头痛,恶心", stored.symptoms)

        // 仍对原成员可见。
        assertEquals(listOf(logId), symptoms.getAllLogs().first().map { it.id })

        // 其他成员看不到。
        val mom = recipients.create("妈妈")
        recipients.setActiveRecipient(mom)
        assertTrue("记录不得移动到其他成员", symptoms.getAllLogs().first().isEmpty())
    }

    private fun medication(name: String) = Medication(name = name, doseUnit = "片")
}
