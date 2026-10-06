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
 * v21 → v22（新增待办表 `care_todos`）迁移测试，docs/todos.md §4。
 *
 * 关键约束：
 * - **纯新增**：既有成员/药品/照护事项/日志逐行保留、内容不变；不触碰任何既有表；
 * - 新表建好后为空、可读写，两条索引（careRecipientId / status）存在；
 * - 成员删除按 FK CASCADE 连带删除其待办；
 * - 同一段迁移也覆盖「恢复的 v21 备份」——被恢复的 v21 库文件在 App 首次打开时
 *   执行的正是 [MedLogDatabase.MIGRATION_21_22]（`BackupCompatibilityPolicy.canRestore`
 *   上界跟随 `DatabaseSchema.VERSION`，旧备份自动放行）。
 */
@RunWith(AndroidJUnit4::class)
class CareTodoMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MedLogDatabase::class.java,
    )

    @After
    fun deleteDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    /**
     * 用一份「有数据的 v21 库」驱动迁移——等价于就地升级，也等价于把一份 v21 备份文件
     * 恢复进应用数据库目录后首次打开（两条路径都走同一段迁移）。
     */
    @Test
    fun migrate21To22_keepsLegacyRows_andAddsWritableCareTodosWithIndicesAndCascade() {
        seedPopulatedV21()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_21_22,
        ).use { db ->
            // (a) 既有行零丢失、内容逐项不变
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_recipients WHERE id = 1"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medication_logs"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals("吸氧", db.text("SELECT title FROM care_tasks WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_task_logs"))

            // (b) 新表存在且为空，可读写
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_todos"))
            db.execSQL(
                """
                INSERT INTO care_todos (
                    careRecipientId, title, status, dueAtMs, sourceType, sourceId, sourceNote,
                    createdAtMs, closedAtMs, resolutionNote
                ) VALUES (
                    1, '让护士看一下压疮风险', 'OPEN', NULL, 'OBSERVATION', NULL, '护工：骶尾处发红',
                    1700000000000, NULL, NULL
                )
                """.trimIndent(),
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_todos WHERE status = 'OPEN'"))
            db.execSQL("UPDATE care_todos SET status = 'DONE', closedAtMs = 1700000005000 WHERE id = 1")
            assertEquals("DONE", db.text("SELECT status FROM care_todos WHERE id = 1"))
            assertEquals(1_700_000_005_000L, db.long("SELECT closedAtMs FROM care_todos WHERE id = 1"))

            // (c) 两条索引存在
            val indices = db.indexNames("care_todos")
            assertTrue(
                "care_todos 缺少 careRecipientId 索引：$indices",
                "index_care_todos_careRecipientId" in indices,
            )
            assertTrue(
                "care_todos 缺少 status 索引：$indices",
                "index_care_todos_status" in indices,
            )

            // (d) 成员删除级联：确认 FK 开启后删除 1 号成员，其待办随之消失
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO care_todos (careRecipientId, title, status, createdAtMs) " +
                    "VALUES (2, '家属：复查预约', 'OPEN', 1700000001000)",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_todos WHERE careRecipientId = 2"))
            db.execSQL("DELETE FROM care_recipients WHERE id = 1")
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_todos WHERE careRecipientId = 1"))
            assertEquals(
                "删除 1 号成员不得影响 2 号成员的待办",
                1,
                db.count("SELECT COUNT(*) FROM care_todos WHERE careRecipientId = 2"),
            )
        }
    }

    @Test
    fun migrate21To22_onEmptyDatabase_createsEmptyCareTodosTableWithIndices() {
        helper.createDatabase(TEST_DATABASE, 21).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_21_22,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_todos"))
            val indices = db.indexNames("care_todos")
            assertTrue("index_care_todos_careRecipientId" in indices)
            assertTrue("index_care_todos_status" in indices)
        }
    }

    private fun seedPopulatedV21() {
        helper.createDatabase(TEST_DATABASE, 21).use { db ->
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
                "INSERT INTO care_task_logs (careTaskId, scheduledTimeMs, status, actualStartMs, actualEndMs, " +
                    "actualDurationMinutes, notes, createdAtMs, revisionType) " +
                    "VALUES (1, 1700000000000, 'DONE', 1700000000000, 1700001800000, 30, '', 1, 'ORIGINAL')",
            )
        }
    }
}

private const val TEST_DATABASE = "care-todo-migration-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else ""
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
