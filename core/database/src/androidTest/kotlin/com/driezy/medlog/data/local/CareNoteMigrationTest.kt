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
 * v22 → v23（新增照护笔记两张表 `care_notes` / `care_note_links`）迁移测试，docs/care-notes.md §8。
 *
 * 关键约束：
 * - **纯新增**：既有成员/药品/照护事项/待办/日志逐行保留、内容不变；不触碰任何既有表；
 * - 新表建好后为空、可读写，两组索引（care_notes: careRecipientId/status；
 *   care_note_links: noteId/(targetType,targetId)）存在；
 * - 删除笔记 → 其 links 由 FK 级联删除；
 * - 删除成员 → 其笔记（及其 links）两跳级联删除，不影响其他成员；
 * - 删除**被关联目标** → link 不删除、不级联、读取不抛错（悬挂容忍，§5）。
 */
@RunWith(AndroidJUnit4::class)
class CareNoteMigrationTest {

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
    fun migrate22To23_keepsLegacyRows_andAddsWritableCareNotesWithIndicesAndCascades() {
        seedPopulatedV22()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_22_23,
            MedLogDatabase.MIGRATION_23_24,
        ).use { db ->
            // (1) 既有行零丢失、内容逐项不变
            assertEquals(2, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medication_logs"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals("吸氧", db.text("SELECT title FROM care_tasks WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_task_logs"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_todos"))
            assertEquals("让护士看一下压疮风险", db.text("SELECT title FROM care_todos WHERE id = 1"))

            // (2) 新表存在且为空，可读写
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_notes"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_note_links"))
            db.execSQL(
                """
                INSERT INTO care_notes (
                    id, careRecipientId, title, body, attributionType, attributionName,
                    attributionAtMs, attributionText, status, supersededText, supersededAtMs,
                    createdAtMs, updatedAtMs
                ) VALUES (
                    1, 1, '护士交代', '饭后半小时服药', 'CLINICIAN', '王医生',
                    1700000000000, '查房时提到', 'ACTIVE', NULL, NULL,
                    1700000000000, NULL
                )
                """.trimIndent(),
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_notes"))
            db.execSQL("UPDATE care_notes SET status = 'SUPERSEDED', supersededText = '改到饭后即服' WHERE id = 1")
            assertEquals("SUPERSEDED", db.text("SELECT status FROM care_notes WHERE id = 1"))
            assertEquals("改到饭后即服", db.text("SELECT supersededText FROM care_notes WHERE id = 1"))

            // (3) 索引存在
            val noteIndices = db.indexNames("care_notes")
            assertTrue(
                "care_notes 缺少 careRecipientId 索引：$noteIndices",
                "index_care_notes_careRecipientId" in noteIndices,
            )
            assertTrue("care_notes 缺少 status 索引：$noteIndices", "index_care_notes_status" in noteIndices)
            val linkIndices = db.indexNames("care_note_links")
            assertTrue("care_note_links 缺少 noteId 索引：$linkIndices", "index_care_note_links_noteId" in linkIndices)
            assertTrue(
                "care_note_links 缺少 (targetType,targetId) 索引：$linkIndices",
                "index_care_note_links_targetType_targetId" in linkIndices,
            )

            db.execSQL("PRAGMA foreign_keys = ON")

            // (4) 删除笔记 → 其 links 级联删除
            db.execSQL("INSERT INTO care_note_links (noteId, targetType, targetId) VALUES (1, 'MEDICATION', 1)")
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_note_links WHERE noteId = 1"))
            db.execSQL("DELETE FROM care_notes WHERE id = 1")
            assertEquals("删除笔记必须级联删除其 links", 0, db.count("SELECT COUNT(*) FROM care_note_links"))

            // (5) 删除成员 → 其笔记（及其 links）级联删除，不影响其他成员
            db.execSQL(
                "INSERT INTO care_notes (careRecipientId, title, body, attributionType, status, createdAtMs) " +
                    "VALUES (1, '爸爸的笔记', '观察记录', 'PERSONAL_OBSERVATION', 'ACTIVE', 1700000001000)",
            )
            val dadNoteId = db.long("SELECT id FROM care_notes WHERE careRecipientId = 1")
            db.execSQL("INSERT INTO care_note_links (noteId, targetType, targetId) VALUES ($dadNoteId, 'CARE_TASK', 1)")
            db.execSQL(
                "INSERT INTO care_notes (careRecipientId, title, body, attributionType, status, createdAtMs) " +
                    "VALUES (2, '妈妈的笔记', '家属记录', 'PERSONAL_OBSERVATION', 'ACTIVE', 1700000002000)",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_notes WHERE careRecipientId = 2"))
            db.execSQL("DELETE FROM care_recipients WHERE id = 1")
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_notes WHERE careRecipientId = 1"))
            assertEquals(
                "删除成员必须两跳级联其笔记的 links",
                0,
                db.count("SELECT COUNT(*) FROM care_note_links WHERE noteId = $dadNoteId"),
            )
            assertEquals(
                "删除 1 号成员不得影响 2 号成员的笔记",
                1,
                db.count("SELECT COUNT(*) FROM care_notes WHERE careRecipientId = 2"),
            )

            // (6) 被关联目标被删除 → link 不删除、读取不抛错（悬挂容忍）
            val momNoteId = db.long("SELECT id FROM care_notes WHERE careRecipientId = 2")
            db.execSQL(
                "INSERT INTO care_note_links (noteId, targetType, targetId) VALUES ($momNoteId, 'MEDICATION', 1)",
            )
            db.execSQL("DELETE FROM medications WHERE id = 1")
            assertEquals(
                "被关联目标删除后 link 必须保留（不级联、不清扫）",
                1,
                db.count("SELECT COUNT(*) FROM care_note_links WHERE noteId = $momNoteId AND targetId = 1"),
            )
            // 读取该 link 不抛错
            assertEquals("MEDICATION", db.text("SELECT targetType FROM care_note_links WHERE noteId = $momNoteId"))
        }
    }

    @Test
    fun migrate22To23_onEmptyDatabase_createsEmptyTablesWithIndices() {
        helper.createDatabase(TEST_DATABASE, 22).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_22_23,
            MedLogDatabase.MIGRATION_23_24,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_notes"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_note_links"))
            val noteIndices = db.indexNames("care_notes")
            assertTrue("index_care_notes_careRecipientId" in noteIndices)
            assertTrue("index_care_notes_status" in noteIndices)
            val linkIndices = db.indexNames("care_note_links")
            assertTrue("index_care_note_links_noteId" in linkIndices)
            assertTrue("index_care_note_links_targetType_targetId" in linkIndices)
        }
    }

    private fun seedPopulatedV22() {
        helper.createDatabase(TEST_DATABASE, 22).use { db ->
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
            db.execSQL(
                "INSERT INTO care_todos (id, careRecipientId, title, status, createdAtMs) " +
                    "VALUES (1, 1, '让护士看一下压疮风险', 'OPEN', 1700000000000)",
            )
        }
    }
}

private const val TEST_DATABASE = "care-note-migration-test"

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
