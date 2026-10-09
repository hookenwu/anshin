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
 * v23 → v24（新增人员档案表 `care_people` + `care_notes.attributionPersonId`）迁移测试，
 * docs/care-people.md §5。
 *
 * 关键约束：
 * - **纯新增、无表重建**：既有成员/药品/照护事项/待办/照护笔记逐行保留、内容不变；不触碰任何既有表；
 * - 新表建好后可读写，两条索引（careRecipientId / name）存在；
 * - 既有 `care_notes` 行的新列 `attributionPersonId` **为 NULL**（零数据搬运）；
 * - `attributionPersonId` **可空且不建外键**（care_notes 仍只挂 care_recipients）——历史由姓名快照保护；
 * - 删除成员 → 其 `care_people` 由 FK **级联删除**（与其笔记一并随成员级联，无悬挂残留），不影响其他成员。
 */
@RunWith(AndroidJUnit4::class)
class CarePeopleMigrationTest {

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
    fun migrate23To24_keepsLegacyRows_andAddsWritableCarePeopleWithIndicesAndCascade() {
        seedPopulatedV23()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_23_24,
        ).use { db ->
            // (1) 既有行零丢失、内容逐项不变（含既有照护笔记的正文与姓名快照）
            assertEquals(2, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_tasks"))
            assertEquals("吸氧", db.text("SELECT title FROM care_tasks WHERE id = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_todos"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_notes"))
            assertEquals("饭后半小时服药", db.text("SELECT body FROM care_notes WHERE id = 1"))
            assertEquals("王医生", db.text("SELECT attributionName FROM care_notes WHERE id = 1"))
            assertEquals("CLINICIAN", db.text("SELECT attributionType FROM care_notes WHERE id = 1"))

            // (2) 既有 care_notes 行的新列 attributionPersonId 必须为 NULL（零数据搬运）
            db.query("SELECT attributionPersonId FROM care_notes WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertTrue("既有笔记的 attributionPersonId 迁移后必须为 NULL", cursor.isNull(0))
            }

            // (2b) 新列可空，且 care_notes 上仍只挂 care_recipients 一个外键（本列刻意不建外键）
            var personIdNotNull = true
            db.query("PRAGMA table_info('care_notes')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "attributionPersonId") {
                        personIdNotNull = cursor.getInt(notNullIndex) != 0
                    }
                }
            }
            assertEquals("attributionPersonId 必须可空", false, personIdNotNull)
            db.query("PRAGMA foreign_key_list('care_notes')").use { cursor ->
                val tableIndex = cursor.getColumnIndexOrThrow("table")
                val referenced = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(tableIndex))
                }
                assertEquals(
                    "care_notes 只允许挂 care_recipients（attributionPersonId 不建外键）",
                    setOf("care_recipients"),
                    referenced,
                )
            }

            // (3) 新表存在且为空，可读写
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_people"))
            db.execSQL(
                "INSERT INTO care_people " +
                    "(careRecipientId, name, gender, approxAge, hospital, agency, phone, createdAtMs) " +
                    "VALUES (1, '护士张', '女', 30, '市一院', '康护', '13800000000', 1700000000000)",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_people WHERE careRecipientId = 1"))
            assertEquals("护士张", db.text("SELECT name FROM care_people WHERE id = 1"))
            db.execSQL("UPDATE care_people SET name = '张护士', updatedAtMs = 1700000005000 WHERE id = 1")
            assertEquals("张护士", db.text("SELECT name FROM care_people WHERE id = 1"))

            // (4) 两条索引存在
            val indices = db.indexNames("care_people")
            assertTrue(
                "care_people 缺少 careRecipientId 索引：$indices",
                "index_care_people_careRecipientId" in indices,
            )
            assertTrue("care_people 缺少 name 索引：$indices", "index_care_people_name" in indices)

            // (5) 成员删除级联：删除 1 号成员，其人员随之消失，不影响 2 号成员
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO care_people (careRecipientId, name, createdAtMs) VALUES (2, '妈妈请的护工', 1700000001000)",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_people WHERE careRecipientId = 2"))
            db.execSQL("DELETE FROM care_recipients WHERE id = 1")
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_people WHERE careRecipientId = 1"))
            assertEquals(
                "删除 1 号成员不得影响 2 号成员的人员",
                1,
                db.count("SELECT COUNT(*) FROM care_people WHERE careRecipientId = 2"),
            )
        }
    }

    @Test
    fun migrate23To24_onEmptyDatabase_createsEmptyCarePeopleTableWithIndices() {
        helper.createDatabase(TEST_DATABASE, 23).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_23_24,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM care_people"))
            val indices = db.indexNames("care_people")
            assertTrue("index_care_people_careRecipientId" in indices)
            assertTrue("index_care_people_name" in indices)
        }
    }

    private fun seedPopulatedV23() {
        helper.createDatabase(TEST_DATABASE, 23).use { db ->
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
            // 一条既有照护笔记：v23 schema 尚无 attributionPersonId 列。
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
        }
    }
}

private const val TEST_DATABASE = "care-people-migration-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else ""
}

private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
    query("PRAGMA index_list('$table')").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
