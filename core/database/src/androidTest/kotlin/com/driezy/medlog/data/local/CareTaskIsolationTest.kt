package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 照护事项数据层验收：成员维度隔离、级联删除、日志唯一键防重。
 *
 * 日志不冗余 careRecipientId，按成员过滤依赖 careTaskId 归属（JOIN care_tasks）。
 */
@RunWith(AndroidJUnit4::class)
class CareTaskIsolationTest {

    private fun inMemoryDatabase(): MedLogDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MedLogDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun tasksAndLogsAreIsolatedPerRecipient() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadTask = database.careTaskDao().insert(task(dad, "吸氧"))
            val momTask = database.careTaskDao().insert(task(mom, "翻身"))
            database.careTaskLogDao().insertLog(log(dadTask, SCHEDULED))
            database.careTaskLogDao().insertLog(log(momTask, SCHEDULED))

            assertEquals(listOf(dadTask), database.careTaskDao().getActiveTasksOnce(dad).map { it.id })
            assertEquals(listOf(momTask), database.careTaskDao().getActiveTasksOnce(mom).map { it.id })

            val dadLogs = database.careTaskLogDao().getLogsForDateOnce(dad, SCHEDULED - 1)
            val momLogs = database.careTaskLogDao().getLogsForDateOnce(mom, SCHEDULED - 1)
            assertEquals(listOf(dadTask), dadLogs.map { it.careTaskId })
            assertEquals(listOf(momTask), momLogs.map { it.careTaskId })

            // 归档只影响自己那一位成员
            database.careTaskDao().setArchived(dadTask, true)
            assertTrue(database.careTaskDao().getActiveTasksOnce(dad).isEmpty())
            assertEquals(1, database.careTaskDao().getAllTasksOnce(dad).size)
            assertEquals(listOf(momTask), database.careTaskDao().getActiveTasksOnce(mom).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingRecipientCascadesTasksAndTheirLogs() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadTask = database.careTaskDao().insert(task(dad, "吸氧"))
            val momTask = database.careTaskDao().insert(task(mom, "翻身"))
            database.careTaskLogDao().insertLog(log(dadTask, SCHEDULED))
            database.careTaskLogDao().insertLog(log(momTask, SCHEDULED))

            database.careRecipientDao().deleteById(dad)

            assertNull(database.careRecipientDao().getById(dad))
            assertNull(database.careTaskDao().getById(dadTask))
            assertEquals(0, database.careTaskLogDao().getLogsForDateOnce(dad, SCHEDULED - 1).size)
            assertNotNull(database.careTaskDao().getById(momTask))
            assertEquals(1, database.careTaskLogDao().getLogsForDateOnce(mom, SCHEDULED - 1).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingTaskCascadesItsLogs() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val taskId = database.careTaskDao().insert(task(dad, "吸氧"))
            database.careTaskLogDao().insertLog(log(taskId, SCHEDULED))

            database.careTaskDao().deleteById(taskId)

            assertEquals(0, database.careTaskLogDao().getLogsForDateOnce(dad, SCHEDULED - 1).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun sameTaskAndScheduledTimeKeepsSingleLogRow() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val taskId = database.careTaskDao().insert(task(dad, "吸氧"))
            val scheduled = SCHEDULED

            database.careTaskLogDao().insertLog(log(taskId, scheduled))
            // 二次写入同一 (careTaskId, scheduledTimeMs)：唯一键 + REPLACE 语义 → 仍只有一行
            val second = log(taskId, scheduled).copy(
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = scheduled + 60_000,
            )
            database.careTaskLogDao().insertLog(second)

            val stored = database.careTaskLogDao().getLogsForDateOnce(dad, SCHEDULED - 1)
            assertEquals(1, stored.size)
            assertEquals(CareTaskLogStatus.IN_PROGRESS, stored.single().status)
            assertEquals(scheduled + 60_000, stored.single().actualStartMs)
        } finally {
            database.close()
        }
    }

    private suspend fun newRecipient(database: MedLogDatabase, name: String): Long =
        database.careRecipientDao().insert(CareRecipient(uuid = CareRecipient.newUuid(), displayName = name))

    private fun task(recipientId: Long, title: String) = CareTask(
        careRecipientId = recipientId,
        title = title,
        category = CareTaskCategory.OTHER,
        completionMode = CareTaskCompletionMode.TOGGLE,
        scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        reminderTimes = "08:00",
    )

    private fun log(careTaskId: Long, scheduledTimeMs: Long) = CareTaskLog(
        careTaskId = careTaskId,
        scheduledTimeMs = scheduledTimeMs,
        status = CareTaskLogStatus.DONE,
        actualEndMs = scheduledTimeMs,
    )
}

/** 固定基准时间戳：日志按 [startMs, startMs + 24h) 窗口查询，测试据此推窗口起点。 */
private const val SCHEDULED = 1_700_000_000_000L
