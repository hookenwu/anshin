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
 * v26 → v27（`medications` 新增 `offsetMinutes`）迁移测试，
 * docs/medication-time-offset.md §4 第 8 条。
 *
 * 关键约束：
 * - **纯新增、零搬运**：`ALTER TABLE ADD COLUMN`，不重建表、不触碰索引与既有列、无 UPDATE 回填；
 * - 老数据的既有字段**逐字不变**（名称/时段/钟点/库存/边界）；
 * - **每个既有药品的 `offsetMinutes` 都必须是 0**（构造上合法，偏移是"未设置"而非"猜一个"）；
 * - 关联表（`medication_logs` / `medication_plan_revisions`）行数与内容零丢失；
 * - `runMigrationsAndValidate(validateDroppedTables = true)` ⇒ 迁移后的真实 schema 必须与导出的
 *   `27.json` 逐字一致（少 `@ColumnInfo(defaultValue = "0")` 会在**打开数据库时**炸在这里）。
 */
@RunWith(AndroidJUnit4::class)
class MedicationTimeOffsetMigrationTest {

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
    fun migrate26To27_onPopulatedDatabase_keepsEveryMedicationAtZeroOffsetAndLosesNothing() {
        val before = seedPopulatedV26()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_26_27,
        ).use { db ->
            // (a) 行数零丢失：5 味药 + 10 条日志 + 5 条修订
            assertEquals(5, db.count("SELECT COUNT(*) FROM medications"))
            assertEquals(10, db.count("SELECT COUNT(*) FROM medication_logs"))
            assertEquals(5, db.count("SELECT COUNT(*) FROM medication_plan_revisions"))

            // (b) 既有字段逐字不变（迁移前后各投影一次，排除新增列后整行比对）
            assertEquals(before, db.medicationSnapshot())

            // (c) 新列存在、NOT NULL、默认 0，且**每一行**都是 0
            assertEquals(5, db.count("SELECT COUNT(*) FROM medications WHERE offsetMinutes = 0"))
            assertEquals(
                0,
                db.count("SELECT COUNT(*) FROM medications WHERE offsetMinutes IS NOT NULL AND offsetMinutes != 0"),
            )
            assertEquals(
                "offsetMinutes 列必须是 INTEGER NOT NULL DEFAULT 0",
                5,
                db.count(
                    "SELECT COUNT(*) FROM medications WHERE offsetMinutes = 0 AND typeof(offsetMinutes) = 'integer'",
                ),
            )

            // (d) 新列可写，且只影响被写的那一行
            db.execSQL("UPDATE medications SET offsetMinutes = 15 WHERE id = 3")
            assertEquals(15, db.count("SELECT offsetMinutes FROM medications WHERE id = 3"))
            assertEquals(4, db.count("SELECT COUNT(*) FROM medications WHERE offsetMinutes = 0"))

            // (e) 既有索引未被破坏（纯新增迁移不重建表）
            val indices = db.indexNames("medications")
            assertTrue("缺少 medications 的 isArchived 索引：$indices", "index_medications_isArchived" in indices)
            assertTrue(
                "缺少 medications 的 careRecipientId 索引：$indices",
                "index_medications_careRecipientId" in indices,
            )
        }
    }

    @Test
    fun migrate26To27_onEmptyDatabase_addsZeroDefaultColumn() {
        helper.createDatabase(TEST_DATABASE, 26).use { /* 空库 */ }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            DatabaseSchema.VERSION,
            true,
            MedLogDatabase.MIGRATION_26_27,
        ).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM medications"))
            db.execSQL(
                "INSERT INTO medications (careRecipientId, name, doseUnit, category, form, isHighPriority, " +
                    "frequencyType, frequencyInterval, frequencyDays, timePeriod, reminderTimes, reminderHour, " +
                    "reminderMinute, doseQuantity, isPRN, startDate, refillReminderDays, notes, isCustomDrug, " +
                    "isArchived, createdAt, isTcm, fullPath, intervalHours, planEffectiveFromMs) VALUES " +
                    "(1, '新药', '片', '', '', 0, 'DAILY', 1, '', 'exact', '08:00', 8, 0, 1.0, 0, 0, 30, '', 0, 0, 1, 0, '', 0, 0)",
            )
            assertEquals("未显式赋值的行必须拿到 0（不是 NULL）", 0, db.count("SELECT offsetMinutes FROM medications"))
        }
    }

    /** 造 v26 库并灌数据：精确时间 / 双时段 / PRN / 间隔 / 归档，各带 2 条日志与 1 条修订。 */
    private fun seedPopulatedV26(): List<String> {
        helper.createDatabase(TEST_DATABASE, 26).use { db ->
            db.execSQL(
                "INSERT INTO care_recipients (id, uuid, displayName, createdAtMs, updatedAtMs) VALUES (1, 'uuid-dad', '爸爸', 1, 1)",
            )
            db.insertMedication(
                id = 1, name = "二甲双胍", timePeriod = "exact", reminderTimes = "08:00",
                reminderHour = 8, reminderMinute = 0, stock = 20.0, isArchived = 0, planEffectiveFromMs = 1000,
            )
            db.insertMedication(
                id = 2, name = "维生素D", timePeriod = "beforeBreakfast,afterDinner", reminderTimes = "07:45,18:15",
                reminderHour = 7, reminderMinute = 45, stock = 30.0, isArchived = 0, planEffectiveFromMs = 2000,
            )
            db.insertMedication(
                id = 3, name = "布洛芬", timePeriod = "exact", reminderTimes = "12:00",
                reminderHour = 12, reminderMinute = 0, stock = null, isArchived = 0, planEffectiveFromMs = 3000,
                isPrn = 1,
            )
            db.insertMedication(
                id = 4, name = "抗生素", timePeriod = "exact", reminderTimes = "09:00",
                reminderHour = 9, reminderMinute = 0, stock = 5.0, isArchived = 0, planEffectiveFromMs = 4000,
                intervalHours = 8,
            )
            db.insertMedication(
                id = 5, name = "阿司匹林", timePeriod = "bedtime", reminderTimes = "22:00",
                reminderHour = 22, reminderMinute = 0, stock = 7.5, isArchived = 1, planEffectiveFromMs = 5000,
            )
            for (medicationId in 1..5) {
                for (slot in 0 until 2) {
                    db.execSQL(
                        "INSERT INTO medication_logs (medicationId, scheduledTimeMs, status, notes, createdAtMs, " +
                            "revisionType) VALUES ($medicationId, ${1700000000000L + medicationId * 100 + slot}, " +
                            "'TAKEN', '', 1, 'ORIGINAL')",
                    )
                }
                db.execSQL(
                    "INSERT INTO medication_plan_revisions (medicationId, effectiveFromMs, effectiveUntilMs, " +
                        "startDate, endDate, frequencyType, frequencyInterval, frequencyDays, timePeriod, " +
                        "reminderTimes, reminderHour, reminderMinute, intervalHours, isPRN, isArchived, " +
                        "doseQuantity, doseUnit) VALUES ($medicationId, 0, 1000, 0, NULL, 'DAILY', 1, '', " +
                        "'exact', '08:00', 8, 0, 0, 0, 0, 1.0, '片')",
                )
            }
            return db.medicationSnapshot()
        }
    }
}

private const val TEST_DATABASE = "medication-time-offset-migration-test"

private fun SupportSQLiteDatabase.insertMedication(
    id: Long,
    name: String,
    timePeriod: String,
    reminderTimes: String,
    reminderHour: Int,
    reminderMinute: Int,
    stock: Double?,
    isArchived: Int,
    planEffectiveFromMs: Long,
    isPrn: Int = 0,
    intervalHours: Int = 0,
) {
    execSQL(
        """
        INSERT INTO medications (
            id, careRecipientId, name, doseUnit, category, form, isHighPriority,
            frequencyType, frequencyInterval, frequencyDays, timePeriod, reminderTimes,
            reminderHour, reminderMinute, doseQuantity, isPRN, startDate, refillReminderDays,
            notes, isCustomDrug, isArchived, createdAt, isTcm, fullPath, intervalHours,
            planEffectiveFromMs, stock
        ) VALUES (
            $id, 1, '$name', '片', '', '', 0,
            'DAILY', 1, '', '$timePeriod', '$reminderTimes',
            $reminderHour, $reminderMinute, 1.0, $isPrn, 0, 30,
            '', 0, $isArchived, 1, 0, '', $intervalHours,
            $planEffectiveFromMs, ${stock ?: "NULL"}
        )
        """.trimIndent(),
    )
}

/** 迁移前/后可比的行快照：显式列 + 固定顺序，避免把新增列混进来。 */
private fun SupportSQLiteDatabase.medicationSnapshot(): List<String> = query(
    "SELECT id, careRecipientId, name, doseUnit, category, form, isHighPriority, frequencyType, " +
        "frequencyInterval, frequencyDays, timePeriod, reminderTimes, reminderHour, reminderMinute, " +
        "doseQuantity, isPRN, startDate, refillReminderDays, notes, isCustomDrug, isArchived, createdAt, " +
        "isTcm, fullPath, intervalHours, planEffectiveFromMs, IFNULL(stock, 'NULL') " +
        "FROM medications ORDER BY id",
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) {
            add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
        }
    }
}

private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
}

private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> =
    query("PRAGMA index_list('$table')").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
