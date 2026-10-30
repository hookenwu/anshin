package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.IllegalTimeOffsetException
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.data.model.validateTimeOffset
import com.driezy.medlog.domain.model.RoutineAnchor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2 的**事务原子性**证据（docs/medication-time-offset.md §3.5，PLAN §4.1）。
 *
 * 生产路径的形状是：一个 `withTransaction` 里逐药 `updatePlan`，偏移闸门在**循环之后**抛出
 * （`PlanRoutineBatch` 先收集冲突再抛 `RoutineResyncConflictException`）。本类钉住那一半不依赖
 * app 模块的事实：**抛点在同一事务内 ⇒ Room 只可能整批提交或整批不落**。
 *
 * 闸门本身（[validateTimeOffset] / `IllegalTimeOffsetException`）在 `:core:model`，这里直接调用
 * **真实**校验函数与**真实**异常类型，不用替身类。唯一看不见的是 app 的包装类型
 * `RoutineResyncConflictException`（`capability.reminders`，core 不可见），它只改变异常的名字，
 * 不改变"在事务内抛出"这件事。
 */
@RunWith(AndroidJUnit4::class)
class MedicationPlanBatchAtomicityTest {

    /**
     * 1) 偏移非法出现在批量的**末尾** ⇒ 同一批里先前已经执行的 `updatePlan` 也一起消失。
     *
     * 三味药：前两味 +15（合法，23:15 / 19:15 式的同日钟点），第三味 +60 落在就寝 23:00 上 ⇒
     * 解析瞬时 24:00 越界（`CROSS_MIDNIGHT`）。前两次写入已经发生，闸门在循环之后才抛。
     */
    @Test
    fun lateIllegalOffsetRollsBackTheEarlierPlanWritesInTheSameBatch() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "爷爷")
            val ids = listOf("早药", "午药", "晚药").map { dao.insertMedication(medication(recipientId, it)) }
            val before = ids.map { dao.getMedicationById(it)!! }
            val bedAtEleven = RoutineSchedule(bed = RoutineTime(23, 0))
            val batch = listOf(
                before[0] to (15 to "22:15"),
                before[1] to (15 to "22:15"),
                before[2] to (60 to "00:00"),
            )

            var writtenInTransaction: List<PlanWriteResult> = emptyList()
            val failure = runCatching {
                database.withTransaction {
                    val rejected = mutableListOf<TimeOffsetCheck.Rejected>()
                    val results = mutableListOf<PlanWriteResult>()
                    batch.forEach { (row, plan) ->
                        val (offset, times) = plan
                        val check = validateTimeOffset(
                            minutes = offset,
                            schedule = bedAtEleven,
                            periods = listOf(RoutineAnchor.BEDTIME),
                            applicable = true,
                        )
                        if (check is TimeOffsetCheck.Rejected) {
                            rejected += check
                        } else {
                            val written = row.copy(
                                reminderTimes = times,
                                reminderHour = 22,
                                reminderMinute = 15,
                            )
                            results += dao.updatePlan(written, 1_000)
                        }
                    }
                    writtenInTransaction = results.toList()
                    if (rejected.isNotEmpty()) throw IllegalTimeOffsetException(rejected.first())
                }
            }

            val error = failure.exceptionOrNull()
            assertTrue("末位的非法偏移必须抛出真实闸门异常，实际：$failure", error is IllegalTimeOffsetException)
            assertEquals(60, (error as IllegalTimeOffsetException).check.minutes)
            assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, error.check.reason)
            assertEquals(
                "非空转：抛点之前那两次 updatePlan 必须真的前进过边界（否则\"回滚\"是空话）",
                listOf(PlanWriteResult.Advanced, PlanWriteResult.Advanced),
                writtenInTransaction,
            )

            assertEquals("整批回滚：前两次写入必须逐字复原", before, ids.map { dao.getMedicationById(it)!! })
            assertTrue(
                "整批回滚：一条修订都不许留下，实际：${dao.observePlanRevisions(recipientId).first()}",
                dao.observePlanRevisions(recipientId).first().isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    /**
     * 2) 失败之后，**逐列**检查没有部分写入：修订表 0 行、钟点缓存三列与边界原值、库存等非计划列也没落。
     *
     * 与用例 1 的区别：这一批里第一味走的是 `Advanced`（本该插修订并前移边界），第二味走的是
     * `NoOp` 的**原地整行 UPDATE**（库存在变）。原地写最容易被漏掉——它不是"没写过"，而是"写过又被
     * 回滚"，所以用原始 SQL 逐列断言，而不是只比实体。
     */
    @Test
    fun rejectedBatchLeavesTheRevisionCacheColumnsAndMedicationRowsUntouched() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "妈妈")
            val advancing = dao.insertMedication(medication(recipientId, "甲").copy(stock = 10.0))
            val inPlace = dao.insertMedication(medication(recipientId, "乙").copy(stock = 10.0))
            val rejected = dao.insertMedication(medication(recipientId, "丙").copy(stock = 10.0))
            val before = listOf(advancing, inPlace, rejected).map { dao.getMedicationById(it)!! }
            val bedAtEleven = RoutineSchedule(bed = RoutineTime(23, 0))

            var resultsInTransaction: List<PlanWriteResult> = emptyList()
            var revisionsInTransaction = -1
            var stockInTransaction = ""
            val failure = runCatching {
                database.withTransaction {
                    // ① 计划变了 ⇒ 插修订 + 边界前移到 1000
                    val advanced = before[0].copy(reminderTimes = "09:15", reminderHour = 9, reminderMinute = 15)
                    // ② 计划没变、只有库存变 ⇒ 原地整行 UPDATE（NoOp）
                    resultsInTransaction = listOf(
                        dao.updatePlan(advanced, 1_000),
                        dao.updatePlan(before[1].copy(stock = 4.0), 1_000),
                    )
                    // 事务内的地面真值：① 的修订与 ② 的库存此刻**确实**已经在库里
                    revisionsInTransaction = database.revisionCount()
                    stockInTransaction = database.stockOf(inPlace)
                    // ③ 第三味药偏移非法，闸门在循环之后抛出 ⇒ ①② 一起回滚
                    val check = validateTimeOffset(60, bedAtEleven, listOf(RoutineAnchor.BEDTIME), applicable = true)
                    throw IllegalTimeOffsetException(check as TimeOffsetCheck.Rejected)
                }
            }

            assertTrue(failure.exceptionOrNull() is IllegalTimeOffsetException)
            assertEquals(
                "非空转：事务内必须真的写过（Advanced + NoOp 原地写）",
                listOf(PlanWriteResult.Advanced, PlanWriteResult.NoOp),
                resultsInTransaction,
            )
            assertEquals("事务内确实插过 1 条修订", 1, revisionsInTransaction)
            assertEquals("事务内库存确实被原地写过", "4.0", stockInTransaction)
            assertEquals("修订表必须 0 行", 0, database.revisionCount())
            for (id in listOf(advancing, inPlace, rejected)) {
                assertEquals(
                    "钟点缓存三列 + 偏移 + 边界必须保持原值（id=$id）",
                    listOf("08:00", "8", "0", "0", "0"),
                    database.cacheColumnsOf(id),
                )
            }
            assertEquals("库存列也不能留下 NoOp 的原地写", "10.0", database.stockOf(inPlace))
            assertEquals("行数不变", 3, database.medicationCount())
            assertEquals(before, listOf(advancing, inPlace, rejected).map { dao.getMedicationById(it)!! })
        } finally {
            database.close()
        }
    }

    /**
     * 3) 成功的一批：**每个真的改了钟点的药恰 1 条修订**，`NoOp` 的药 0 条。
     *
     * 三味药：甲 08:00→09:15、乙 08:00→21:45、丙钟点不变只改库存。修订记的是**旧**计划
     * （`reminderTimes = 08:00`, `from = 0`, `until = 1000`），现行行是新钟点、边界 1000；
     * 丙的边界仍是 0。
     */
    @Test
    fun successfulBatchWritesExactlyOneRevisionPerChangedMedicationAndNoneForANoOp() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "本人")
            val first = dao.insertMedication(medication(recipientId, "甲"))
            val second = dao.insertMedication(medication(recipientId, "乙"))
            val noOp = dao.insertMedication(medication(recipientId, "丙"))
            val stored = listOf(first, second, noOp).map { dao.getMedicationById(it)!! }

            val results = database.withTransaction {
                listOf(
                    dao.updatePlan(
                        stored[0].copy(reminderTimes = "09:15", reminderHour = 9, reminderMinute = 15),
                        1_000,
                    ),
                    dao.updatePlan(
                        stored[1].copy(reminderTimes = "21:45", reminderHour = 21, reminderMinute = 45),
                        1_000,
                    ),
                    dao.updatePlan(stored[2].copy(stock = 4.0), 1_000),
                )
            }

            assertEquals(listOf(PlanWriteResult.Advanced, PlanWriteResult.Advanced, PlanWriteResult.NoOp), results)
            assertEquals("两次真实改动 ⇒ 恰 2 条修订", 2, database.revisionCount())

            val revisions = dao.observePlanRevisions(recipientId).first()
            val perMedication = revisions.groupBy { it.medicationId }
            assertEquals("甲恰 1 条", 1, perMedication[first]?.size ?: 0)
            assertEquals("乙恰 1 条", 1, perMedication[second]?.size ?: 0)
            assertNull("NoOp 的药一条修订都不许有", perMedication[noOp])

            for (id in listOf(first, second)) {
                val revision = perMedication.getValue(id).single()
                assertEquals("修订记的是旧计划", "08:00", revision.reminderTimes)
                assertEquals(0L, revision.effectiveFromMs)
                assertEquals(1_000L, revision.effectiveUntilMs)
                assertEquals("边界前移", 1_000L, dao.getMedicationById(id)!!.planEffectiveFromMs)
            }
            assertEquals("09:15", dao.getMedicationById(first)!!.reminderTimes)
            assertEquals("21:45", dao.getMedicationById(second)!!.reminderTimes)
            assertEquals("NoOp 不动钟点", "08:00", dao.getMedicationById(noOp)!!.reminderTimes)
            assertEquals("NoOp 不动边界", 0L, dao.getMedicationById(noOp)!!.planEffectiveFromMs)
            assertEquals("NoOp 的非计划列照常落库", 4.0, dao.getMedicationById(noOp)!!.stock!!, 0.0)
        } finally {
            database.close()
        }
    }

    /**
     * 4) 批量中途的 `PlanBoundaryNotAdvancedException`（时钟回拨 / 同毫秒重复写）同样整批回滚。
     *
     * 甲的边界已经是 5000 且有 1 条修订；本批在 6000 上成功写了甲、乙，第三味丙用 `changedAt = 0`
     * 触发真实拒绝 ⇒ 甲、乙的写入必须消失，修订链回到批次前的**那一条**。
     */
    @Test
    fun planBoundaryRejectionInTheMiddleOfTheBatchRollsTheWholeBatchBack() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "奶奶")
            val first = dao.insertMedication(medication(recipientId, "甲"))
            val second = dao.insertMedication(medication(recipientId, "乙"))
            val third = dao.insertMedication(medication(recipientId, "丙"))
            dao.updatePlan(dao.getMedicationById(first)!!.copy(reminderTimes = "07:30"), 5_000)
            val rowsBefore = listOf(first, second, third).map { dao.getMedicationById(it)!! }
            val revisionsBefore = dao.observePlanRevisions(recipientId).first()
            assertEquals(1, revisionsBefore.size)

            var resultsInTransaction: List<PlanWriteResult> = emptyList()
            var revisionsInTransaction = -1
            val failure = runCatching {
                database.withTransaction {
                    resultsInTransaction = listOf(
                        dao.updatePlan(rowsBefore[0].copy(reminderTimes = "09:15"), 6_000),
                        dao.updatePlan(rowsBefore[1].copy(reminderTimes = "19:15"), 6_000),
                    )
                    // 事务内的地面真值：1 条历史修订 + 本批 2 条 = 3
                    revisionsInTransaction = database.revisionCount()
                    // 丙的边界是 0，changedAt = 0 没有"严格越过" ⇒ 真实拒绝，抛在事务内
                    dao.updatePlan(rowsBefore[2].copy(reminderTimes = "13:15"), 0)
                }
            }

            val error = failure.exceptionOrNull()
            assertTrue("必须是真实的边界拒绝，实际：$failure", error is PlanBoundaryNotAdvancedException)
            assertEquals(third, (error as PlanBoundaryNotAdvancedException).medicationId)
            assertEquals(0L, error.requestedMs)
            assertEquals(
                "非空转：甲、乙在事务内确实前进过边界",
                listOf(PlanWriteResult.Advanced, PlanWriteResult.Advanced),
                resultsInTransaction,
            )
            assertEquals("事务内确实累计到 3 条修订", 3, revisionsInTransaction)

            assertEquals("甲、乙的写入必须一起回滚", rowsBefore, listOf(first, second, third).map { dao.getMedicationById(it)!! })
            assertEquals(
                "修订链必须回到批次前的那一条",
                revisionsBefore,
                dao.observePlanRevisions(recipientId).first(),
            )
            assertEquals(1, database.revisionCount())
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

    /** 钟点缓存三列 + 偏移 + 边界，按原始列读（不经过实体映射）。 */
    private fun MedLogDatabase.cacheColumnsOf(id: Long): List<String> = openHelper.readableDatabase
        .query(
            "SELECT reminderTimes, reminderHour, reminderMinute, offsetMinutes, planEffectiveFromMs " +
                "FROM medications WHERE id = ?",
            arrayOf<Any>(id),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "medication $id missing" }
            listOf(
                cursor.getString(0),
                cursor.getInt(1).toString(),
                cursor.getInt(2).toString(),
                cursor.getInt(3).toString(),
                cursor.getLong(4).toString(),
            )
        }

    private fun MedLogDatabase.stockOf(id: Long): String = openHelper.readableDatabase
        .query("SELECT stock FROM medications WHERE id = ?", arrayOf<Any>(id))
        .use { cursor ->
            check(cursor.moveToFirst()) { "medication $id missing" }
            cursor.getDouble(0).toString()
        }

    private fun MedLogDatabase.revisionCount(): Int = openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM medication_plan_revisions")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun MedLogDatabase.medicationCount(): Int = openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM medications")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
