package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.Medication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicationDaoTransactionTest {
    @Test
    fun replaceActiveMedicationsRollsBackDeletionWhenAnInsertFails() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "爸爸")
            dao.insertMedication(medication(recipientId, "existing"))
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_replacement
                BEFORE INSERT ON medications
                WHEN NEW.name = 'failure'
                BEGIN
                    SELECT RAISE(ABORT, 'simulated insert failure');
                END
                """.trimIndent(),
            )

            val failure = runCatching {
                dao.replaceActiveMedications(
                    recipientId,
                    listOf(medication(recipientId, "replacement"), medication(recipientId, "failure")),
                )
            }

            assertTrue(failure.isFailure)
            assertEquals(listOf("existing"), dao.getAllMedicationsOnce(recipientId).map(Medication::name))
        } finally {
            database.close()
        }
    }

    @Test
    fun planChangesKeepOldScheduleAndStockWritesDoNotCreateRevisions() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "妈妈")
            val id = dao.insertMedication(
                medication(recipientId, "existing").copy(startDate = 0, stock = 10.0),
            )
            val original = dao.getMedicationById(id)!!
            dao.updatePlan(original.copy(reminderTimes = "20:00", doseQuantity = 2.0), 1000)
            dao.updateStock(id, recipientId, 8.0)
            val revisions = dao.observePlanRevisions(recipientId).first()
            assertEquals(1, revisions.size)
            assertEquals("08:00", revisions.single().reminderTimes)
            assertEquals(1.0, revisions.single().doseQuantity, 0.0)
            assertEquals(1000L, revisions.single().effectiveUntilMs)
            assertEquals(1000L, dao.getMedicationById(id)!!.planEffectiveFromMs)
            dao.updatePlan(dao.getMedicationById(id)!!.copy(isArchived = true), 2000)
            dao.updatePlan(dao.getMedicationById(id)!!.copy(isArchived = false), 3000)
            assertEquals(
                listOf(false, false, true),
                dao.observePlanRevisions(recipientId).first().map { it.isArchived },
            )
            assertEquals(8.0, dao.getMedicationById(id)!!.stock!!, 0.0)
        } finally {
            database.close()
        }
    }

    @Test
    fun planRevisionAndCurrentScheduleRollbackTogether() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "本人")
            val id = dao.insertMedication(medication(recipientId, "existing"))
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_update BEFORE UPDATE ON medications BEGIN SELECT RAISE(ABORT, 'simulated failure'); END",
            )
            assertTrue(
                runCatching {
                    dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "20:00"), 1000)
                }.isFailure,
            )
            assertTrue(dao.observePlanRevisions(recipientId).first().isEmpty())
            assertEquals("08:00", dao.getMedicationById(id)!!.reminderTimes)
        } finally {
            database.close()
        }
    }

    /**
     * B2：作息重算整批被拒（冲突收集后抛出）⇒ 已经写过的行、它们的修订、边界**一并回滚**。
     *
     * 这里用与 `RoutineResyncConflictException` 同形的事务内抛出（app 模块的类型 core 不可见），
     * 钉的是同一件事：抛出点在同一事务里，Room 只可能整批提交或整批不落。
     */
    @Test
    fun batchWritesAndRevisionsRollBackTogetherWhenTheResyncIsRejected() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "爷爷")
            val first = dao.insertMedication(medication(recipientId, "first"))
            val second = dao.insertMedication(medication(recipientId, "second"))
            val boundaryBefore = dao.getMedicationById(first)!!.planEffectiveFromMs

            val rejected = runCatching {
                database.withTransaction {
                    dao.updatePlan(dao.getMedicationById(first)!!.copy(reminderTimes = "09:15"), 1000)
                    dao.updatePlan(dao.getMedicationById(second)!!.copy(reminderTimes = "19:15"), 1000)
                    // 冲突收集完毕后的抛出点（§3.5）：仍在事务内 ⇒ 上面两次写必须一起消失
                    throw IllegalStateException("routine resync rejected the whole batch")
                }
            }

            assertTrue(rejected.isFailure)
            assertEquals("08:00", dao.getMedicationById(first)!!.reminderTimes)
            assertEquals("08:00", dao.getMedicationById(second)!!.reminderTimes)
            assertEquals(boundaryBefore, dao.getMedicationById(first)!!.planEffectiveFromMs)
            assertEquals(boundaryBefore, dao.getMedicationById(second)!!.planEffectiveFromMs)
            assertTrue(
                "整批回滚：一条修订都不许留下",
                dao.observePlanRevisions(recipientId).first().isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    private fun inMemoryDatabase(): MedLogDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MedLogDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun newRecipient(database: MedLogDatabase, displayName: String): Long =
        database.careRecipientDao().insert(CareRecipient(displayName = displayName))

    private fun medication(recipientId: Long, name: String) = Medication(
        careRecipientId = recipientId,
        name = name,
        doseUnit = "tablet",
    )
}
