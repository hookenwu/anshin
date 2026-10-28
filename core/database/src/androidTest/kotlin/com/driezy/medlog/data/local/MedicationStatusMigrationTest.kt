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
 * v25 → v26（新增 `medication_status_events` + 遗留基线补种）迁移测试，
 * docs/medication-status-spec.md §12 / §12.2。
 *
 * 关键约束：
 * - **纯新增、无表重建**：既有成员/药品/计划版本/日志逐行保留、内容不变；不触碰任何既有表；
 * - 新表建好后可读写；两条索引存在；
 * - **为每个 `isArchived = 1` 的遗留药补种恰一条 `LEGACY_BASELINE`（`effectiveAtMs = NULL`）**，
 *   且 `isArchived = 0` 的药**不补种**、`medications` / `medication_logs` **零覆写**；
 * - 迁移**不伪造日期**：基线 `effectiveAtMs` 为 NULL；
 * - 删除药 → 其事件由 FK **级联删除**。
 */
@RunWith(AndroidJUnit4::class)
class MedicationStatusMigrationTest {

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
    fun migrate25To26_onPopulatedDatabase_seedsOneBaselinePerArchivedMedicationAndKeepsEverything() {
        seedPopulatedV25()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_25_26,
            MedLogDatabase.MIGRATION_26_27,
        ).use { db ->
            // (a) 既有行零丢失、内容逐项不变
            assertEquals(1, db.count("SELECT COUNT(*) FROM care_recipients"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals("二甲双胍", db.text("SELECT name FROM medications WHERE id = 1"))
            assertEquals("阿司匹林", db.text("SELECT name FROM medications WHERE id = 2"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM medication_logs"))

            // (b) 新表存在
            assertTrue("medication_status_events 表必须存在", db.tableExists("medication_status_events"))
            // 种子（见 seedPopulatedV25）只有 **1 味**归档药（id=1，isArchived=1；id=2 活跃），迁移按
            // 「每个 isArchived=1 恰一条 LEGACY_BASELINE」补种 ⇒ 全表总数 = 1，与下方逐药断言
            // （id=1 恰 1 条、id=2 恰 0 条）自洽。旧断言写 2 是笔误（与 (c)/(d) 自相矛盾）。
            assertEquals(1, db.count("SELECT COUNT(*) FROM medication_status_events"))

            // (c) 恰一条 LEGACY_BASELINE，生效日显式 NULL（不伪造日期），recordedAtMs 为迁移时刻附近
            val archived = db.count("SELECT COUNT(*) FROM medication_status_events WHERE medicationId = 1")
            assertEquals("归档药恰一条基线行", 1, archived)
            assertEquals("LEGACY_BASELINE", db.text("SELECT kind FROM medication_status_events WHERE medicationId = 1"))
            assertEquals(
                null,
                db.longOrNull("SELECT effectiveAtMs FROM medication_status_events WHERE medicationId = 1"),
            )
            val recordedAt = db.longOrNull("SELECT recordedAtMs FROM medication_status_events WHERE medicationId = 1")
            assertTrue("baseline recordedAtMs 必须是迁移时刻", recordedAt != null && recordedAt > 0L)

            // (d) 活跃药（isArchived=0）不补种
            assertEquals(0, db.count("SELECT COUNT(*) FROM medication_status_events WHERE medicationId = 2"))

            // (e) 两条索引存在
            val indices = db.indexNames("medication_status_events")
            assertTrue(
                "缺少 medicationId 索引：$indices",
                "index_medication_status_events_medicationId" in indices,
            )
            assertTrue(
                "缺少 (medicationId,effectiveAtMs) 索引：$indices",
                "index_medication_status_events_medicationId_effectiveAtMs" in indices,
            )

            // (f) 无零事件遗留归档药
            assertEquals(
                "不存在 isArchived=1 且事件表为空的遗留行",
                0,
                db.count(
                    "SELECT COUNT(*) FROM medications WHERE isArchived = 1 AND id NOT IN " +
                        "(SELECT DISTINCT medicationId FROM medication_status_events)",
                ),
            )

            // (g) 新表可读写
            db.execSQL(
                "INSERT INTO medication_status_events " +
                    "(medicationId, kind, effectiveAtMs, recordedAtMs, reason, supersedesId) " +
                    "VALUES (2, 'PAUSE', 1700000000000, 1700000000000, '医生调整', NULL)",
            )
            assertEquals(
                1,
                db.count("SELECT COUNT(*) FROM medication_status_events WHERE medicationId = 2 AND kind = 'PAUSE'"),
            )

            // (h) 删除药级联删事件（含基线）
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM medications WHERE id = 1")
            assertEquals(0, db.count("SELECT COUNT(*) FROM medication_status_events WHERE medicationId = 1"))
        }
    }

    @Test
    fun migrate25To26_onEmptyDatabase_createsEmptyTableWithIndexes() {
        helper.createDatabase(TEST_DATABASE, 25).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_25_26,
            MedLogDatabase.MIGRATION_26_27,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM medication_status_events"))
            assertTrue("index_medication_status_events_medicationId" in db.indexNames("medication_status_events"))
            assertTrue(
                "index_medication_status_events_medicationId_effectiveAtMs" in
                    db.indexNames("medication_status_events"),
            )
        }
    }

    private fun seedPopulatedV25() {
        helper.createDatabase(TEST_DATABASE, 25).use { db ->
            db.execSQL(
                "INSERT INTO care_recipients (id, uuid, displayName, createdAtMs, updatedAtMs) " +
                    "VALUES (1, 'uuid-dad', '爸爸', 1, 1)",
            )
            // id=1 已归档（遗留），id=2 活跃
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
                    'DAILY', 1, '', 'exact', '08:00',
                    8, 0, 1.0, 0, 0, 30,
                    '', 0, 1, 1, 0, '', 0,
                    0
                )
                """.trimIndent(),
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
                    2, 1, '阿司匹林', '片', '抗血小板', '', 0,
                    'DAILY', 1, '', 'exact', '20:00',
                    20, 0, 1.0, 0, 0, 30,
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
                "INSERT INTO medication_logs (medicationId, scheduledTimeMs, status, notes, createdAtMs, " +
                    "revisionType) VALUES (2, 1700000001000, 'TAKEN', '', 1, 'ORIGINAL')",
            )
        }
    }
}

private const val TEST_DATABASE = "medication-status-migration-test"

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else ""
}

private fun SupportSQLiteDatabase.longOrNull(sql: String): Long? = query(sql).use { cursor ->
    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
}

private fun SupportSQLiteDatabase.tableExists(name: String): Boolean =
    query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { cursor ->
        cursor.count > 0
    }

private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
    query("PRAGMA index_list('$table')").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
