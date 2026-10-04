package com.driezy.medlog.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v18 → v19（引入 CareRecipient）迁移测试。
 *
 * 关键约束：
 * - 有历史人员数据时，必须创建一个兼容档案并把历史行全部归入该档案，且不丢行；
 * - 空库迁移不得创建任何成员（新安装由"添加成员"建立档案）。
 */
@RunWith(AndroidJUnit4::class)
class CareRecipientMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MedLogDatabase::class.java,
    )

    @After
    fun deleteDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrate18To19_withLegacyData_createsCompatibilityRecipientAndKeepsEveryRow() {
        helper.createDatabase(TEST_DATABASE, 18).use { db ->
            db.execSQL(
                """
                INSERT INTO medications (
                    name, dose, doseUnit, category, form, isHighPriority, frequencyType,
                    frequencyInterval, frequencyDays, timePeriod, reminderTimes, reminderHour,
                    reminderMinute, doseQuantity, isPRN, maxDailyDose, startDate, endDate, stock,
                    refillThreshold, refillReminderDays, notes, isCustomDrug, isArchived, createdAt,
                    isTcm, fullPath, intervalHours, planEffectiveFromMs
                ) VALUES (
                    '阿司匹林', 1.0, '片', '消化道及代谢', 'tablet', 0, 'daily', 1, '1,2,3,4,5,6,7',
                    'morning', '07:00', 7, 0, 1.0, 0, NULL, 1790956800000, NULL, 10.0, 2.0, 7, '',
                    0, 0, 1790956800000, 0, '', 0, 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO medication_logs (
                    medicationId, scheduledTimeMs, actualTakenTimeMs, status, notes,
                    actualDoseQuantity, stockDeducted, createdAtMs, updatedAtMs, revisionType
                ) VALUES (1, 1790982000000, 1791041679601, 'TAKEN', '', 1.0, 1.0, 1791041679606, NULL, 'ORIGINAL')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO health_records (
                    type, value, secondaryValue, timestamp, notes, source, confirmedAt
                ) VALUES ('BLOOD_PRESSURE', 120.0, 80.0, 1791041679601, '', 'MANUAL', NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO symptom_logs (
                    recordedAt, overallRating, symptoms, sideEffects, note, medicationId, medicationName
                ) VALUES (1791041679601, 4, '头痛', '', '', 1, '阿司匹林')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 19, true, MedLogDatabase.MIGRATION_18_19)
            .use { db ->
                val recipientId = db.count("SELECT COUNT(*) FROM care_recipients").also {
                    assertEquals("有历史数据时必须创建恰好一个兼容档案", 1, it)
                }.let {
                    db.query("SELECT id, uuid, displayName FROM care_recipients").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("本人", cursor.getString(2))
                        assertTrue("uuid 必须被保留", cursor.getString(1).isNotBlank())
                        cursor.getLong(0)
                    }
                }

                assertEquals(
                    "历史药品必须归入兼容档案且不丢行",
                    1,
                    db.count("SELECT COUNT(*) FROM medications WHERE careRecipientId = $recipientId"),
                )
                assertEquals(
                    1,
                    db.count("SELECT COUNT(*) FROM medication_logs"),
                )
                assertEquals(
                    1,
                    db.count("SELECT COUNT(*) FROM health_records WHERE careRecipientId = $recipientId"),
                )
                assertEquals(
                    1,
                    db.count("SELECT COUNT(*) FROM symptom_logs WHERE careRecipientId = $recipientId"),
                )
                db.query(
                    "SELECT stock, doseQuantity FROM medications WHERE careRecipientId = $recipientId",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(10.0, cursor.getDouble(0), 0.0001)
                    assertEquals(1.0, cursor.getDouble(1), 0.0001)
                }
            }
    }

    @Test
    fun migrate18To19_withoutPersonData_createsNoRecipient() {
        helper.createDatabase(TEST_DATABASE, 18).close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 19, true, MedLogDatabase.MIGRATION_18_19)
            .use { db ->
                assertEquals(
                    "空库迁移不得预置任何成员",
                    0,
                    db.count("SELECT COUNT(*) FROM care_recipients"),
                )
            }
    }

    @Test
    fun freshlyCreatedDatabase_hasNoRecipient() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = androidx.room.Room.databaseBuilder(
            context,
            MedLogDatabase::class.java,
            FRESH_DATABASE,
        ).build()
        try {
            // 不经过任何迁移、由 Room 直接建库（等同新安装）时，不得存在默认成员。
            assertEquals(0, database.careRecipientDao().count())
            val version = database.openHelper.readableDatabase
                .query("PRAGMA user_version").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else -1
                }
            assertEquals(DatabaseSchema.VERSION, version)
        } finally {
            database.close()
            context.deleteDatabase(FRESH_DATABASE)
        }
    }
}

private const val TEST_DATABASE = "care-recipient-migration-test"

private const val FRESH_DATABASE = "care-recipient-fresh-install-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}
