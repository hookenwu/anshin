package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import kotlinx.coroutines.flow.Flow

/**
 * 照护事项 SSOT 仓库。
 *
 * 与 MedicationRepository 同一套成员维度约定：查询按"当前家庭成员"过滤，
 * 写入前绑定当前成员；未选择成员时读返回空、写直接报错。
 */
interface CareTaskRepository {

    fun getActiveTasks(): Flow<List<CareTask>>

    fun getArchivedTasks(): Flow<List<CareTask>>

    suspend fun getActiveTasksOnce(): List<CareTask>

    /** 指定成员的活跃事项；供按成员重排提醒等"非当前成员"场景使用。 */
    suspend fun getTasksFor(recipientId: Long): List<CareTask>

    /** 含已归档：重排提醒时清理归档事项的残留提醒。 */
    suspend fun getAllTasksFor(recipientId: Long): List<CareTask>

    suspend fun getTaskById(id: Long): CareTask?

    suspend fun addTask(task: CareTask): Long

    suspend fun updateTask(task: CareTask)

    suspend fun setArchived(id: Long, archived: Boolean)

    suspend fun deleteTask(id: Long)

    fun getLogsForTask(careTaskId: Long): Flow<List<CareTaskLog>>

    /** 今日页/小组件：当前成员从 startMs 起 24 小时内的日志。 */
    suspend fun getLogsForToday(startMs: Long): List<CareTaskLog>

    suspend fun getLogsForRange(startMs: Long, endMs: Long): List<CareTaskLog>

    suspend fun getLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long): CareTaskLog?

    suspend fun upsertLog(log: CareTaskLog): Long

    suspend fun updateLog(log: CareTaskLog)

    suspend fun deleteLog(log: CareTaskLog)

    suspend fun deleteLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long)
}
