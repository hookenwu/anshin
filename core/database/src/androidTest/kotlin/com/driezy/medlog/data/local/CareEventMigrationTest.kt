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
 * v24 → v25（新增照护事件日志表 `care_event_logs`）迁移测试，docs/tracked-events-spec.md §4/§8。
 *
 * 关键约束：
 * - **纯新增、无表重建**：既有成员/药品/日志/照护事项/待办逐行保留、内容不变；不触碰任何既有表；
 * - 新表建好后可读写，复合索引 `(careRecipientId, kind, occurredAtMs)` 存在；
 * - 删除成员 → 其事件由 FK **级联删除**，不影响其他成员；
 * - 同一段迁移也覆盖「恢复的 v24 备份」——被恢复的 v24 库首次打开时执行的正是
 *   [MedLogDatabase.MIGRATION_24_25]（`BackupCompatibilityPolicy.canRestore` 上界跟随 `DatabaseSchema.VERSION`）。
 */
@RunWith(AndroidJUnit4::class)
class CareEventMigrationTest {

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
    fun migrate24To25_keepsLegacyRows_andAddsWritableCareEventLogsWithIndexAndCascade() {
        seedPopulatedV24()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_24_25,
        ).use { db ->
            // (a) 既有行零丢失、内容逐项不变
            assertEquals(2, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medication_logs"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals("吸氧", db.text("SELECT title FROM care_tasks WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_todos"))
            assertEquals("让护士看一下压疮风险", db.text("SELECT title FROM care_todos WHERE id = 1"))

            // (b) 新表存在且为空，可读写（双时间戳：发生 vs 记录）
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_event_logs"))
            db.execSQL(
                """
                INSERT INTO care_event_logs
                    (careRecipientId, kind, occurredAtMs, note, createdAtMs, updatedAtMs)
                VALUES (1, 'BOWEL', 1700000000000, '补记：昨天下午', 1700003600000, NULL)
                """.trimIndent(),
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_event_logs WHERE kind = 'BOWEL'"))
            assertEquals(1_700_000_000_000L, db.long("SELECT occurredAtMs FROM care_event_logs WHERE id = 1"))
            assertEquals(null, db.textOrNull("SELECT note FROM care_event_logs WHERE kind = 'SOMETHING_ELSE'"))
            db.execSQL("UPDATE care_event_logs SET occurredAtMs = 1699990000000, updatedAtMs = 1700004000000 WHERE id = 1")
            assertEquals(1_699_990_000_000L, db.long("SELECT occurredAtMs FROM care_event_logs WHERE id = 1"))
            assertEquals(1_700_004_000_000L, db.long("SELECT updatedAtMs FROM care_event_logs WHERE id = 1"))

            // (c) 复合索引存在
            val indices = db.indexNames("care_event_logs")
            assertTrue(
                "care_event_logs 缺少 (careRecipientId,kind,occurredAtMs) 索引：$indices",
                "index_care_event_logs_careRecipientId_kind_occurredAtMs" in indices,
            )

            // (d) 成员删除级联：删除 1 号成员，其事件随之消失，不影响 2 号成员
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO care_event_logs (careRecipientId, kind, occurredAtMs, createdAtMs) " +
                    "VALUES (2, 'BOWEL', 1700000001000, 1700000001000)",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_event_logs WHERE careRecipientId = 2"))
            db.execSQL("DELETE FROM care_recipients WHERE id = 1")
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_event_logs WHERE careRecipientId = 1"))
            assertEquals(
                "删除 1 号成员不得影响 2 号成员的事件",
                1,
                db.count("SELECT COUNT(*) FROM care_event_logs WHERE careRecipientId = 2"),
            )
        }
    }

    @Test
    fun migrate24To25_onEmptyDatabase_createsEmptyCareEventLogsTableWithIndex() {
        helper.createDatabase(TEST_DATABASE, 24).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_24_25,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_event_logs"))
            assertTrue("index_care_event_logs_careRecipientId_kind_occurredAtMs" in db.indexNames("care_event_logs"))
        }
    }

    private fun seedPopulatedV24() {
        helper.createDatabase(TEST_DATABASE, 24).use { db ->
            db.execSQL(
                "INSERT INTO care_recipients (id, uuid, displayName, createdAtMs, updatedAtMs) " +
                    "VALUES (1, 'uuid-dad', '爸爸', 1, 1), (2, 'uuid-mom', '妈妈', 1, 1)",
            )
            db.execSQL(
                """
                INSERT INTO medications (
                    id, careRecipientId, name, doseUnit, category, form, isHighPriority,
                    frequencyType, frequencyInterval, frequencyDays, timePeriod, reminderTimes,
                    reminderHour, reminderMinute, doseQuantity, isPRN, startDate, refillReminderDays,
                    notes, isCustomDrug, isArchived, createdAt, isTcm, fullPath, intervalHours,
                    planEffectiveFromMs
                ) VALUES (
                    1, 1, '二甲双胍', '片', '降糖', '', 0,
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
            db.execSQL(
                """
                INSERT INTO care_tasks (
                    id, careRecipientId, title, category, completionMode, scheduleKind, timePeriods,
                    reminderTimes, intervalHours, frequencyType, frequencyInterval, frequencyDays,
                    startDate, notes, isArchived, createdAt
                ) VALUES (
                    1, 1, '吸氧', '呼吸治疗', 'TOGGLE', 'FIXED_TIMES', '',
                    '08:00,14:00,20:00', 0, 'DAILY', 1, '',
                    0, '', 0, 1
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO care_todos (id, careRecipientId, title, status, createdAtMs) " +
                    "VALUES (1, 1, '让护士看一下压疮风险', 'OPEN', 1700000000000)",
            )
        }
    }
}

private const val TEST_DATABASE = "care-event-migration-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else ""
}

private fun SupportSQLiteDatabase.textOrNull(sql: String): String? = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
}

private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
}

private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
    query("PRAGMA index_list('$table')").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
