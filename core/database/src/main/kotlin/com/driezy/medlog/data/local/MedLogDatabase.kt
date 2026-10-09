package com.driezy.medlog.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.driezy.medlog.data.model.AiAnalysisCacheEntry
import com.driezy.medlog.data.model.AiUsageEvent
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteLink
import com.driezy.medlog.data.model.CarePerson
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.SymptomLog

@Database(
    entities = [
        CareRecipient::class,
        CareTask::class,
        CareTaskLog::class,
        CareTodo::class,
        CareNote::class,
        CareNoteLink::class,
        CarePerson::class,
        Medication::class,
        MedicationLog::class,
        MedicationPlanRevision::class,
        SymptomLog::class,
        HealthRecord::class,
        AiAnalysisCacheEntry::class,
        AiUsageEvent::class,
    ],
    version = DatabaseSchema.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MedLogDatabase : RoomDatabase() {
    abstract fun careRecipientDao(): CareRecipientDao
    abstract fun careTaskDao(): CareTaskDao
    abstract fun careTaskLogDao(): CareTaskLogDao
    abstract fun careTodoDao(): CareTodoDao
    abstract fun careNoteDao(): CareNoteDao
    abstract fun carePersonDao(): CarePersonDao
    abstract fun medicationDao(): MedicationDao
    abstract fun medicationLogDao(): MedicationLogDao
    abstract fun symptomLogDao(): SymptomLogDao
    abstract fun healthRecordDao(): HealthRecordDao
    abstract fun aiAnalysisCacheDao(): AiAnalysisCacheDao
    abstract fun aiUsageEventDao(): AiUsageEventDao

    companion object {
        /** 迁移兼容档案名：v18 及更早版本没有成员概念，历史数据统一归入该档案。 */
        private const val LEGACY_RECIPIENT_NAME = "本人"

        /**
         * v20 → v21：用药「规格（单粒强度）」建模 + 清理死列 `dose`。
         *
         * 纯结构变更：新增两列可空规格（`doseStrength` / `doseStrengthUnit`，成对），
         * 删除零读取的死列 `dose`（NOT NULL）→ medications 按 Room 的表重建模式迁移，
         * 其余列与全部数据原样搬运，无回填。原有两条索引随表重建一并恢复。
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `_new_medications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `doseUnit` TEXT NOT NULL,
                        `doseStrength` REAL,
                        `doseStrengthUnit` TEXT,
                        `category` TEXT NOT NULL,
                        `form` TEXT NOT NULL,
                        `isHighPriority` INTEGER NOT NULL,
                        `frequencyType` TEXT NOT NULL,
                        `frequencyInterval` INTEGER NOT NULL,
                        `frequencyDays` TEXT NOT NULL,
                        `timePeriod` TEXT NOT NULL,
                        `reminderTimes` TEXT NOT NULL,
                        `reminderHour` INTEGER NOT NULL,
                        `reminderMinute` INTEGER NOT NULL,
                        `doseQuantity` REAL NOT NULL,
                        `isPRN` INTEGER NOT NULL,
                        `maxDailyDose` REAL,
                        `startDate` INTEGER NOT NULL,
                        `endDate` INTEGER,
                        `stock` REAL,
                        `refillThreshold` REAL,
                        `refillReminderDays` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `isCustomDrug` INTEGER NOT NULL,
                        `isArchived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `isTcm` INTEGER NOT NULL,
                        `fullPath` TEXT NOT NULL,
                        `intervalHours` INTEGER NOT NULL,
                        `planEffectiveFromMs` INTEGER NOT NULL,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `_new_medications` (
                        `id`, `careRecipientId`, `name`, `doseUnit`, `category`, `form`,
                        `isHighPriority`, `frequencyType`, `frequencyInterval`, `frequencyDays`,
                        `timePeriod`, `reminderTimes`, `reminderHour`, `reminderMinute`,
                        `doseQuantity`, `isPRN`, `maxDailyDose`, `startDate`, `endDate`, `stock`,
                        `refillThreshold`, `refillReminderDays`, `notes`, `isCustomDrug`,
                        `isArchived`, `createdAt`, `isTcm`, `fullPath`, `intervalHours`,
                        `planEffectiveFromMs`
                    )
                    SELECT
                        `id`, `careRecipientId`, `name`, `doseUnit`, `category`, `form`,
                        `isHighPriority`, `frequencyType`, `frequencyInterval`, `frequencyDays`,
                        `timePeriod`, `reminderTimes`, `reminderHour`, `reminderMinute`,
                        `doseQuantity`, `isPRN`, `maxDailyDose`, `startDate`, `endDate`, `stock`,
                        `refillThreshold`, `refillReminderDays`, `notes`, `isCustomDrug`,
                        `isArchived`, `createdAt`, `isTcm`, `fullPath`, `intervalHours`,
                        `planEffectiveFromMs`
                    FROM `medications`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `medications`")
                db.execSQL("ALTER TABLE `_new_medications` RENAME TO `medications`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medications_isArchived` " +
                        "ON `medications` (`isArchived`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medications_careRecipientId` " +
                        "ON `medications` (`careRecipientId`)",
                )
            }
        }

        /**
         * v21 → v22：新增待办表 `care_todos`（docs/todos.md §4）。
         *
         * 照 `MIGRATION_19_20` 的先例，**纯新增**：只建表 + 两条索引，不触碰任何既有表与数据。
         * 同样适用于「恢复的 v21 备份」——`BackupCompatibilityPolicy.canRestore` 上界跟随
         * [DatabaseSchema.VERSION]，被恢复的 v21 库在 App 首次打开时执行的正是这段迁移。
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_todos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `dueAtMs` INTEGER,
                        `sourceType` TEXT,
                        `sourceId` INTEGER,
                        `sourceNote` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `closedAtMs` INTEGER,
                        `resolutionNote` TEXT,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_todos_careRecipientId` " +
                        "ON `care_todos` (`careRecipientId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_todos_status` ON `care_todos` (`status`)",
                )
            }
        }

        /**
         * v22 → v23：新增照护笔记两张表 `care_notes` / `care_note_links`（docs/care-notes.md §8）。
         *
         * 照 `MIGRATION_21_22` 的先例，**纯新增**：只建表 + 索引，无表重建、不触碰任何既有表与数据。
         * `care_note_links.targetId` 刻意不建外键（悬挂容忍，读忽略、不清扫）；只有 `noteId`
         * 与 `careRecipientId` 是强制 FK CASCADE。同样适用于「恢复的 v22 备份」——被恢复的
         * v22 库在 App 首次打开时执行的正是这段迁移。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `attributionType` TEXT NOT NULL,
                        `attributionName` TEXT,
                        `attributionAtMs` INTEGER,
                        `attributionText` TEXT,
                        `status` TEXT NOT NULL,
                        `supersededText` TEXT,
                        `supersededAtMs` INTEGER,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_notes_careRecipientId` " +
                        "ON `care_notes` (`careRecipientId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_notes_status` ON `care_notes` (`status`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_note_links` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `noteId` INTEGER NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` INTEGER NOT NULL,
                        FOREIGN KEY(`noteId`) REFERENCES `care_notes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_note_links_noteId` " +
                        "ON `care_note_links` (`noteId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_note_links_targetType_targetId` " +
                        "ON `care_note_links` (`targetType`, `targetId`)",
                )
            }
        }

        /**
         * v23 → v24：新增人员档案表 `care_people` + `care_notes.attributionPersonId`（docs/care-people.md §5）。
         *
         * 照 `MIGRATION_22_23` 的先例，**纯新增、无表重建、零数据搬运**：
         * - `CREATE TABLE care_people`（成员归属 FK CASCADE）+ 两条索引（careRecipientId / name）；
         * - `ALTER TABLE care_notes ADD COLUMN attributionPersonId`（**可空、不建外键**）。
         * `attributionPersonId` 不建外键：历史由 `attributionName` 快照保护，删除人员后**容忍悬挂**，
         * 本仓对跨实体引用一律「无外键 + 读取容忍」。既有 `care_notes` 行的该列迁移后为 NULL。
         * 同样适用于「恢复的 v23 备份」——被恢复的 v23 库首次打开时执行的正是这段迁移。
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_people` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `gender` TEXT,
                        `approxAge` INTEGER,
                        `hospital` TEXT,
                        `agency` TEXT,
                        `phone` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_people_careRecipientId` " +
                        "ON `care_people` (`careRecipientId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_people_name` ON `care_people` (`name`)",
                )
                db.execSQL("ALTER TABLE `care_notes` ADD COLUMN `attributionPersonId` INTEGER")
            }
        }

        /**
         * v19 → v20：新增照护事项两张表（CareTask / CareTaskLog）。
         *
         * 纯新增，不改任何既有列：两表分别挂 CareRecipient / CareTask 外键（CASCADE），
         * 日志唯一键 (careTaskId, scheduledTimeMs) 复用用药的防重方式。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `completionMode` TEXT NOT NULL,
                        `defaultDurationMinutes` INTEGER,
                        `scheduleKind` TEXT NOT NULL,
                        `timePeriods` TEXT NOT NULL,
                        `reminderTimes` TEXT NOT NULL,
                        `intervalHours` INTEGER NOT NULL,
                        `frequencyType` TEXT NOT NULL,
                        `frequencyInterval` INTEGER NOT NULL,
                        `frequencyDays` TEXT NOT NULL,
                        `startDate` INTEGER NOT NULL,
                        `endDate` INTEGER,
                        `notes` TEXT NOT NULL,
                        `isArchived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_tasks_careRecipientId` " +
                        "ON `care_tasks` (`careRecipientId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_tasks_isArchived` ON `care_tasks` (`isArchived`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_task_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careTaskId` INTEGER NOT NULL,
                        `scheduledTimeMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `actualStartMs` INTEGER,
                        `actualEndMs` INTEGER,
                        `actualDurationMinutes` INTEGER,
                        `postureNote` TEXT,
                        `notes` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER,
                        `revisionType` TEXT NOT NULL,
                        FOREIGN KEY(`careTaskId`) REFERENCES `care_tasks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_task_logs_careTaskId` " +
                        "ON `care_task_logs` (`careTaskId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_task_logs_scheduledTimeMs` " +
                        "ON `care_task_logs` (`scheduledTimeMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_task_logs_revisionType` " +
                        "ON `care_task_logs` (`revisionType`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_care_task_logs_careTaskId_scheduledTimeMs` " +
                        "ON `care_task_logs` (`careTaskId`, `scheduledTimeMs`)",
                )
            }
        }

        /**
         * v18 → v19：引入一级实体 CareRecipient。
         *
         * - 新建 care_recipients 表；
         * - 仅当库内存在历史人员数据（药品/症状/健康记录任一非空）时创建一个兼容档案，
         *   并把历史行回填到该档案下；空库不创建任何成员（新安装由"添加成员"建立档案）；
         * - medications / symptom_logs / health_records 增加 careRecipientId（NOT NULL + FK CASCADE），
         *   三张表按 Room 的表重建模式迁移；
         * - health_records 的唯一索引由 (sourceCacheKey) 改为 (careRecipientId, sourceCacheKey)。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_recipients` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_care_recipients_uuid` " +
                        "ON `care_recipients` (`uuid`)",
                )

                val legacyRows = db.query(
                    """
                    SELECT (SELECT COUNT(*) FROM medications)
                         + (SELECT COUNT(*) FROM symptom_logs)
                         + (SELECT COUNT(*) FROM health_records)
                    """.trimIndent(),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

                val now = System.currentTimeMillis()
                val recipientId = if (legacyRows > 0L) {
                    db.execSQL(
                        "INSERT INTO `care_recipients` " +
                            "(`uuid`, `displayName`, `createdAtMs`, `updatedAtMs`) VALUES (?, ?, ?, ?)",
                        arrayOf<Any?>(CareRecipient.newUuid(), LEGACY_RECIPIENT_NAME, now, now),
                    )
                    db.query("SELECT `id` FROM `care_recipients` ORDER BY `id` LIMIT 1")
                        .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                } else {
                    0L
                }

                // ── medications：加 careRecipientId + FK，表重建 ──────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `_new_medications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `dose` REAL NOT NULL,
                        `doseUnit` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `form` TEXT NOT NULL,
                        `isHighPriority` INTEGER NOT NULL,
                        `frequencyType` TEXT NOT NULL,
                        `frequencyInterval` INTEGER NOT NULL,
                        `frequencyDays` TEXT NOT NULL,
                        `timePeriod` TEXT NOT NULL,
                        `reminderTimes` TEXT NOT NULL,
                        `reminderHour` INTEGER NOT NULL,
                        `reminderMinute` INTEGER NOT NULL,
                        `doseQuantity` REAL NOT NULL,
                        `isPRN` INTEGER NOT NULL,
                        `maxDailyDose` REAL,
                        `startDate` INTEGER NOT NULL,
                        `endDate` INTEGER,
                        `stock` REAL,
                        `refillThreshold` REAL,
                        `refillReminderDays` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `isCustomDrug` INTEGER NOT NULL,
                        `isArchived` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `isTcm` INTEGER NOT NULL,
                        `fullPath` TEXT NOT NULL,
                        `intervalHours` INTEGER NOT NULL,
                        `planEffectiveFromMs` INTEGER NOT NULL,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `_new_medications` (
                        `id`, `careRecipientId`, `name`, `dose`, `doseUnit`, `category`, `form`,
                        `isHighPriority`, `frequencyType`, `frequencyInterval`, `frequencyDays`,
                        `timePeriod`, `reminderTimes`, `reminderHour`, `reminderMinute`,
                        `doseQuantity`, `isPRN`, `maxDailyDose`, `startDate`, `endDate`, `stock`,
                        `refillThreshold`, `refillReminderDays`, `notes`, `isCustomDrug`,
                        `isArchived`, `createdAt`, `isTcm`, `fullPath`, `intervalHours`,
                        `planEffectiveFromMs`
                    )
                    SELECT
                        `id`, ?, `name`, `dose`, `doseUnit`, `category`, `form`,
                        `isHighPriority`, `frequencyType`, `frequencyInterval`, `frequencyDays`,
                        `timePeriod`, `reminderTimes`, `reminderHour`, `reminderMinute`,
                        `doseQuantity`, `isPRN`, `maxDailyDose`, `startDate`, `endDate`, `stock`,
                        `refillThreshold`, `refillReminderDays`, `notes`, `isCustomDrug`,
                        `isArchived`, `createdAt`, `isTcm`, `fullPath`, `intervalHours`,
                        `planEffectiveFromMs`
                    FROM `medications`
                    """.trimIndent(),
                    arrayOf(recipientId),
                )
                db.execSQL("DROP TABLE `medications`")
                db.execSQL("ALTER TABLE `_new_medications` RENAME TO `medications`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medications_isArchived` " +
                        "ON `medications` (`isArchived`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medications_careRecipientId` " +
                        "ON `medications` (`careRecipientId`)",
                )

                // ── symptom_logs：加 careRecipientId + FK，表重建 ────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `_new_symptom_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        `overallRating` INTEGER NOT NULL,
                        `symptoms` TEXT NOT NULL,
                        `sideEffects` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `medicationId` INTEGER NOT NULL,
                        `medicationName` TEXT NOT NULL,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `_new_symptom_logs` (
                        `id`, `careRecipientId`, `recordedAt`, `overallRating`, `symptoms`,
                        `sideEffects`, `note`, `medicationId`, `medicationName`
                    )
                    SELECT
                        `id`, ?, `recordedAt`, `overallRating`, `symptoms`,
                        `sideEffects`, `note`, `medicationId`, `medicationName`
                    FROM `symptom_logs`
                    """.trimIndent(),
                    arrayOf(recipientId),
                )
                db.execSQL("DROP TABLE `symptom_logs`")
                db.execSQL("ALTER TABLE `_new_symptom_logs` RENAME TO `symptom_logs`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_symptom_logs_recordedAt` " +
                        "ON `symptom_logs` (`recordedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_symptom_logs_medicationId` " +
                        "ON `symptom_logs` (`medicationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_symptom_logs_careRecipientId` " +
                        "ON `symptom_logs` (`careRecipientId`)",
                )

                // ── health_records：加 careRecipientId + FK，唯一索引改为按成员 ──
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `_new_health_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `careRecipientId` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `value` REAL NOT NULL,
                        `secondaryValue` REAL,
                        `timestamp` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `sourceFeature` TEXT,
                        `sourceProvider` TEXT,
                        `sourceModel` TEXT,
                        `sourceConfidence` REAL,
                        `sourceCacheKey` TEXT,
                        `confirmedAt` INTEGER,
                        FOREIGN KEY(`careRecipientId`) REFERENCES `care_recipients`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `_new_health_records` (
                        `id`, `careRecipientId`, `type`, `value`, `secondaryValue`, `timestamp`,
                        `notes`, `source`, `sourceFeature`, `sourceProvider`, `sourceModel`,
                        `sourceConfidence`, `sourceCacheKey`, `confirmedAt`
                    )
                    SELECT
                        `id`, ?, `type`, `value`, `secondaryValue`, `timestamp`,
                        `notes`, `source`, `sourceFeature`, `sourceProvider`, `sourceModel`,
                        `sourceConfidence`, `sourceCacheKey`, `confirmedAt`
                    FROM `health_records`
                    """.trimIndent(),
                    arrayOf(recipientId),
                )
                db.execSQL("DROP TABLE `health_records`")
                db.execSQL("ALTER TABLE `_new_health_records` RENAME TO `health_records`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_health_records_type` " +
                        "ON `health_records` (`type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_health_records_timestamp` " +
                        "ON `health_records` (`timestamp`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_health_records_source` " +
                        "ON `health_records` (`source`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_health_records_careRecipientId` " +
                        "ON `health_records` (`careRecipientId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_health_records_careRecipientId_sourceCacheKey` " +
                        "ON `health_records` (`careRecipientId`, `sourceCacheKey`)",
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN planEffectiveFromMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medication_logs ADD COLUMN stockDeducted REAL")
                db.execSQL(
                    """
                    UPDATE medication_logs SET actualDoseQuantity = (
                        SELECT doseQuantity FROM medications WHERE id = medicationId
                    ) WHERE status = 'TAKEN' AND actualDoseQuantity IS NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS medication_plan_revisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        medicationId INTEGER NOT NULL,
                        effectiveFromMs INTEGER NOT NULL,
                        effectiveUntilMs INTEGER NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER,
                        frequencyType TEXT NOT NULL,
                        frequencyInterval INTEGER NOT NULL,
                        frequencyDays TEXT NOT NULL,
                        timePeriod TEXT NOT NULL,
                        reminderTimes TEXT NOT NULL,
                        reminderHour INTEGER NOT NULL,
                        reminderMinute INTEGER NOT NULL,
                        intervalHours INTEGER NOT NULL,
                        isPRN INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        doseQuantity REAL NOT NULL,
                        doseUnit TEXT NOT NULL,
                        FOREIGN KEY(medicationId) REFERENCES medications(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_plan_revisions_medicationId " +
                        "ON medication_plan_revisions (medicationId)",
                )
            }
        }

        /** v5 → v6: 添加 intervalHours 列（间隔给药小时数） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN intervalHours INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6 → v7: 添加 refillReminderDays 列（按天数估算备货提醒） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN refillReminderDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7 → v8: 新增 health_records 表（健康体征记录） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        value REAL NOT NULL,
                        secondaryValue REAL,
                        timestamp INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v8 → v9: 为 medications.isArchived 添加索引（加速已存档/未存档过滤查询） */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medications_isArchived ON medications (isArchived)",
                )
            }
        }

        /** v9 → v10: 为 symptom_logs 和 health_records 添加查询索引 */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_symptom_logs_recordedAt ON symptom_logs (recordedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_symptom_logs_medicationId ON symptom_logs (medicationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_records_type ON health_records (type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_records_timestamp ON health_records (timestamp)")
            }
        }

        /** v10 → v11: medication_logs 新增 actualDoseQuantity（部分服用剂量）列 */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medication_logs ADD COLUMN actualDoseQuantity REAL")
            }
        }

        /** v11 → v12: medication_logs 添加复合索引 (medicationId, scheduledTimeMs) 以加速多条件查询 */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_logs_medicationId_scheduledTimeMs ON medication_logs (medicationId, scheduledTimeMs)",
                )
            }
        }

        /** v12 → v13: 新增 AI 结果缓存和轻量本地审计表 */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_analysis_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        promptVersion INTEGER NOT NULL,
                        inputHash TEXT NOT NULL,
                        locale TEXT NOT NULL,
                        responseJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_analysis_cache_kind ON ai_analysis_cache (kind)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_analysis_cache_expiresAt ON ai_analysis_cache (expiresAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_analysis_cache_kind_createdAt ON ai_analysis_cache (kind, createdAt)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_usage_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        feature TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        networkType TEXT NOT NULL,
                        cacheHit INTEGER NOT NULL,
                        result TEXT NOT NULL,
                        errorCategory TEXT,
                        inputHashPrefix TEXT,
                        latencyMs INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_events_timestamp ON ai_usage_events (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_events_feature ON ai_usage_events (feature)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_usage_events_result ON ai_usage_events (result)")
            }
        }

        /** v13 → v14: 健康记录增加来源 provenance，用于区分手动、本地 OCR、云端 OCR 和导入记录 */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_records ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE health_records ADD COLUMN sourceFeature TEXT")
                db.execSQL("ALTER TABLE health_records ADD COLUMN sourceProvider TEXT")
                db.execSQL("ALTER TABLE health_records ADD COLUMN sourceModel TEXT")
                db.execSQL("ALTER TABLE health_records ADD COLUMN sourceConfidence REAL")
                db.execSQL("ALTER TABLE health_records ADD COLUMN sourceCacheKey TEXT")
                db.execSQL("ALTER TABLE health_records ADD COLUMN confirmedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_records_source ON health_records (source)")
            }
        }

        /** v14 → v15: medication_logs 增加修订元数据，用于区分当天编辑与过期后补改。 */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medication_logs ADD COLUMN createdAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE medication_logs ADD COLUMN updatedAtMs INTEGER")
                db.execSQL("ALTER TABLE medication_logs ADD COLUMN revisionType TEXT NOT NULL DEFAULT 'ORIGINAL'")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_logs_revisionType ON medication_logs (revisionType)",
                )
            }
        }

        /** v15 → v16: one medication can have at most one log for a scheduled occurrence. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM medication_logs
                    WHERE id NOT IN (
                        SELECT MAX(id)
                        FROM medication_logs
                        GROUP BY medicationId, scheduledTimeMs
                    )
                    """.trimIndent(),
                )
                db.execSQL("DROP INDEX IF EXISTS index_medication_logs_medicationId_scheduledTimeMs")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_medication_logs_medicationId_scheduledTimeMs
                    ON medication_logs (medicationId, scheduledTimeMs)
                    """.trimIndent(),
                )
            }
        }

        /** v16 → v17: 为 sourceCacheKey 添加唯一索引，从数据库层阻止重复导入。迁移前清洗空字符串并去重，避免历史重复数据导致迁移失败。 */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 将空字符串转为 NULL，避免多个空字符串违反 UNIQUE 约束
                db.execSQL(
                    """
                    UPDATE health_records
                    SET sourceCacheKey = NULL
                    WHERE sourceCacheKey IS NOT NULL AND TRIM(sourceCacheKey) = ''
                    """.trimIndent(),
                )
                // 对历史存量重复的非空 sourceCacheKey 追加 ":migrated_<id>" 保证唯一，确保不丢失任何历史健康记录
                db.execSQL(
                    """
                    UPDATE health_records
                    SET sourceCacheKey = sourceCacheKey || ':migrated_' || id
                    WHERE sourceCacheKey IS NOT NULL
                      AND sourceCacheKey IN (
                          SELECT sourceCacheKey
                          FROM health_records
                          WHERE sourceCacheKey IS NOT NULL
                          GROUP BY sourceCacheKey
                          HAVING COUNT(*) > 1
                      )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_health_records_sourceCacheKey
                    ON health_records (sourceCacheKey)
                    """.trimIndent(),
                )
            }
        }
    }
}
