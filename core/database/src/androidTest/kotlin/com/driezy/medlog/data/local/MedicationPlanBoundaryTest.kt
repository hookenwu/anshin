package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 计划修订链的划分性（docs/medication-time-offset.md §3.2 三分支、§8 第 15/16 条）。
 *
 * 旧实现（`maxOf` / "跳过插入但把边界改写成 changedAt"）在这两条测试里**必红**：
 * 同毫秒或回拨的内容变更会让上一条修订的 `until` 大于新边界（区间重叠），或让前一段变成零长度
 * （历史被吃掉）。现在的规则是：内容相同 ⇒ 原地 NoOp；内容不同且严格前进 ⇒ 插修订；否则 ⇒ 拒绝 + 零写入。
 */
@RunWith(AndroidJUnit4::class)
class MedicationPlanBoundaryTest {

    @Test
    fun planChainStaysPartitionedUnderDuplicateAndBackwardWrites() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "爸爸")
            val id = dao.insertMedication(medication(recipientId, "二甲双胍"))
            assertEquals(0L, dao.getMedicationById(id)!!.planEffectiveFromMs)

            // ① 正常前进：内容变了、时间戳前进 ⇒ 一条修订 + 边界前移
            val first = dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "09:00"), 5000)
            assertEquals(PlanWriteResult.Advanced, first)
            val afterFirst = dao.getMedicationById(id)!!
            assertEquals(5000L, afterFirst.planEffectiveFromMs)
            assertEquals(1, dao.observePlanRevisions(recipientId).first().size)

            val rowBefore = afterFirst
            val revisionsBefore = dao.observePlanRevisions(recipientId).first()

            // ② 同毫秒、内容不同 ⇒ 拒绝（不覆盖、不重叠）
            val duplicate = runCatching { dao.updatePlan(rowBefore.copy(reminderTimes = "21:00"), 5000) }
            val duplicateError = duplicate.exceptionOrNull()
            assertTrue("同毫秒内容变更必须被拒绝，实际：$duplicate", duplicateError is PlanBoundaryNotAdvancedException)
            assertEquals(id, (duplicateError as PlanBoundaryNotAdvancedException).medicationId)
            assertEquals(5000L, duplicateError.boundaryMs)
            assertEquals(5000L, duplicateError.requestedMs)

            // ③ 时钟回拨、内容不同 ⇒ 同样拒绝
            val backward = runCatching { dao.updatePlan(rowBefore.copy(reminderTimes = "19:00"), 1000) }
            val backwardError = backward.exceptionOrNull()
            assertTrue("回拨写入必须被拒绝，实际：$backward", backwardError is PlanBoundaryNotAdvancedException)
            assertEquals(1000L, (backwardError as PlanBoundaryNotAdvancedException).requestedMs)

            // ④ 行与修订**逐字**不变（异常在 @Transaction 内 ⇒ 零写入）
            assertEquals(rowBefore, dao.getMedicationById(id))
            assertEquals(revisionsBefore, dao.observePlanRevisions(recipientId).first())

            // ⑤ 区间是划分：相邻修订首尾相接、最后一条接当前边界、每段长度 > 0
            assertChain(dao.observePlanRevisions(recipientId).first(), dao.getMedicationById(id)!!)

            // ⑥ 再前进一次：链继续相邻，过去那条的 until 不再改变
            val second = dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "22:00"), 9000)
            assertEquals(PlanWriteResult.Advanced, second)
            val revisions = dao.observePlanRevisions(recipientId).first()
            assertEquals(2, revisions.size)
            assertEquals(revisionsBefore.single().effectiveUntilMs, revisions.first().effectiveUntilMs)
            assertEquals(5000L, revisions.first().effectiveUntilMs)
            assertNotEquals(revisions.first().reminderTimes, revisions.last().reminderTimes)
            assertChain(revisions, dao.getMedicationById(id)!!)
        } finally {
            database.close()
        }
    }

    @Test
    fun identicalContentWriteAtTheSameTimestampIsATrueNoOpAndKeepsTheBoundary() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "妈妈")
            val id = dao.insertMedication(medication(recipientId, "维生素D").copy(startDate = 0, stock = 10.0))
            dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "09:00"), 5000)
            val revisionsBefore = dao.observePlanRevisions(recipientId).first()

            // 计划字段逐字相同 + 同毫秒 ⇒ 原地写整行，返回 NoOp，0 修订，边界不动
            val noOp = dao.updatePlan(dao.getMedicationById(id)!!.copy(stock = 4.0, notes = "剩 4 片"), 5000)
            assertEquals(PlanWriteResult.NoOp, noOp)
            val row = dao.getMedicationById(id)!!
            assertEquals(5000L, row.planEffectiveFromMs)
            assertEquals("09:00", row.reminderTimes)
            assertEquals(4.0, row.stock!!, 0.0)
            assertEquals("剩 4 片", row.notes) // 非计划列照常落库
            assertEquals(revisionsBefore, dao.observePlanRevisions(recipientId).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun offsetOnlyChangeIsNoOpBecauseTheOffsetIsNotPartOfTheRevisionSnapshot() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "本人")
            val id = dao.insertMedication(medication(recipientId, "阿司匹林"))
            dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "21:00"), 5000)
            val revisionsBefore = dao.observePlanRevisions(recipientId).first()

            // 只有 offsetMinutes 变、钟点没变 ⇒ 计划内容相同 ⇒ NoOp（偏移不进修订快照，§3.1）
            val noOp = dao.updatePlan(dao.getMedicationById(id)!!.copy(offsetMinutes = 15), 6000)
            assertEquals(PlanWriteResult.NoOp, noOp)
            assertEquals(15, dao.getMedicationById(id)!!.offsetMinutes)
            assertEquals(5000L, dao.getMedicationById(id)!!.planEffectiveFromMs)
            assertEquals(revisionsBefore, dao.observePlanRevisions(recipientId).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectedWriteLeavesNoRevisionBehindEvenWhenItWouldHaveBeenTheFirst() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.medicationDao()
            val recipientId = newRecipient(database, "本人")
            val id = dao.insertMedication(medication(recipientId, "布洛芬"))
            // 边界已经是 0（默认），回拨写入连第一条修订都不能产生
            val failure = runCatching { dao.updatePlan(dao.getMedicationById(id)!!.copy(reminderTimes = "06:00"), 0) }
            assertTrue(failure.exceptionOrNull() is PlanBoundaryNotAdvancedException)
            assertTrue(dao.observePlanRevisions(recipientId).first().isEmpty())
            assertEquals("08:00", dao.getMedicationById(id)!!.reminderTimes)
        } finally {
            database.close()
        }
    }

    /** I3：`r_i.until == r_{i+1}.from`、`last.until == 当前边界`、每段 `until > from`。 */
    private fun assertChain(revisions: List<MedicationPlanRevision>, current: Medication) {
        assertTrue("修订链为空时无法校验", revisions.isNotEmpty())
        for ((previous, next) in revisions.zipWithNext()) {
            assertEquals(
                "修订区间出现洞或重叠：${previous.effectiveUntilMs} vs ${next.effectiveFromMs}",
                previous.effectiveUntilMs,
                next.effectiveFromMs,
            )
        }
        assertEquals(revisions.last().effectiveUntilMs, current.planEffectiveFromMs)
        assertTrue(
            "每段必须是非零长度：${revisions.map { it.effectiveFromMs to it.effectiveUntilMs }}",
            revisions.all { it.effectiveUntilMs > it.effectiveFromMs },
        )
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
}
