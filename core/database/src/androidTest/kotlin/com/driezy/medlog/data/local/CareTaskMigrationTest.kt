package com.driezy.medlog.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v19 → v20（新增照护事项两张表）迁移测试。
 *
 * 关键约束：
 * - 纯新增：既有成员/药品/日志逐行保留、内容不变；
 * - 空库迁移后新表存在且为空，不创建任何默认照护事项；
 * - 新表的列/外键/唯一索引必须通过 Room 的 schema 校验（runMigrationsAndValidate）。
 */
@RunWith(AndroidJUnit4::class)
class CareTaskMigrationTest {

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
    fun migrate19To20_withExistingData_keepsLegacyRowsAndAddsEmptyCareTaskTables() {
        helper.createDatabase(TEST_DATABASE, 19).use { db ->
            db.execSQL(
                "INSERT INTO care_recipients (uuid, displayName, createdAtMs, updatedAtMs) " +
                    "VALUES ('uuid-dad', '爸爸', 1, 1)",
            )
            db.execSQL(
                """
                INSERT INTO medications (
                    careRecipientId, name, dose, doseUnit, category, form, isHighPriority,
                    frequencyType, frequencyInterval, frequencyDays, timePeriod, reminderTimes,
                    reminderHour, reminderMinute, doseQuantity, isPRN, startDate, refillReminderDays,
                    notes, isCustomDrug, isArchived, createdAt, isTcm, fullPath, intervalHours,
                    planEffectiveFromMs
                ) VALUES (
                    1, '二甲双胍', 1.0, '片', '降糖', '', 0,
                    'DAILY', 1, '', '', '08:00',
                    8, 0, 1.0, 0, 0, 30,
                    '', 0, 0, 1, 0, '', 0,
                    0
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO medication_logs (medicationId, scheduledTimeMs, status, notes, createdAtMs, " +
                    "revisionType) VALUES (1, 1700000000000, 'TAKEN', '', 1, 'ORIGINAL')",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            MedLogDatabase.MIGRATION_19_20,
        ).use { db ->
            // 既有数据一行不少、内容不变
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medication_logs"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))

            // 新表已建好且为空（不创建任何默认照护事项）
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_task_logs"))

            // 新表可写入，且唯一键 (careTaskId, scheduledTimeMs) 生效
            db.execSQL(
                """
                INSERT INTO care_tasks (
                    careRecipientId, title, category, completionMode, scheduleKind, timePeriods,
                    reminderTimes, intervalHours, frequencyType, frequencyInterval, frequencyDays,
                    startDate, notes, isArchived, createdAt
                ) VALUES (
                    1, '吸氧', '呼吸治疗', 'DURATION', 'FIXED_TIMES', '',
                    '08:00,14:00,20:00', 0, 'DAILY', 1, '',
                    0, '', 0, 1
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO care_task_logs (careTaskId, scheduledTimeMs, status, actualStartMs, actualEndMs, " +
                    "actualDurationMinutes, notes, createdAtMs, revisionType) " +
                    "VALUES (1, 1700000000000, 'DONE', 1700000000000, 1700001800000, 30, '', 1, 'ORIGINAL')",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_task_logs"))
        }
    }

    @Test
    fun migrate19To20_onEmptyDatabase_createsEmptyCareTaskTables() {
        helper.createDatabase(TEST_DATABASE, 19).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            MedLogDatabase.MIGRATION_19_20,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_task_logs"))
        }
    }
}

private const val TEST_DATABASE = "care-task-migration-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else ""
}
