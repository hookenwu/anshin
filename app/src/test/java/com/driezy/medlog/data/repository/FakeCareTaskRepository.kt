package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 照护事项仓储的内存假件：成员作用域在单测里不参与（VM/契约测试只关心 state 与命令分派），
 * 因此所有读直接返回当前集合，写就地更新并分配自增 id。
 */
class FakeCareTaskRepository : CareTaskRepository {

    private val tasks = MutableStateFlow<List<CareTask>>(emptyList())
    private val logs = MutableStateFlow<List<CareTaskLog>>(emptyList())
    private var nextTaskId = 1L
    private var nextLogId = 1L

    fun seedTask(task: CareTask): Long {
        val id = if (task.id != 0L) task.id else nextTaskId++
        tasks.value = tasks.value + task.copy(id = id)
        return id
    }

    fun seedLog(log: CareTaskLog) {
        logs.value = logs.value + log
    }

    fun storedTasks(): List<CareTask> = tasks.value

    fun storedLogs(): List<CareTaskLog> = logs.value

    override fun getActiveTasks(): Flow<List<CareTask>> = tasks.map { list -> list.filter { !it.isArchived } }

    override fun getArchivedTasks(): Flow<List<CareTask>> = tasks.map { list -> list.filter { it.isArchived } }

    override suspend fun getActiveTasksOnce(): List<CareTask> = tasks.value.filter { !it.isArchived }

    override suspend fun getTasksFor(recipientId: Long): List<CareTask> = tasks.value.filter { !it.isArchived }

    override suspend fun getAllTasksFor(recipientId: Long): List<CareTask> = tasks.value

    override suspend fun getTaskById(id: Long): CareTask? = tasks.value.firstOrNull { it.id == id }

    override suspend fun addTask(task: CareTask): Long {
        val id = nextTaskId++
        tasks.value = tasks.value + task.copy(id = id)
        return id
    }

    override suspend fun updateTask(task: CareTask) {
        tasks.value = tasks.value.map { if (it.id == task.id) task else it }
    }

    override suspend fun setArchived(id: Long, archived: Boolean) {
        tasks.value = tasks.value.map { if (it.id == id) it.copy(isArchived = archived) else it }
    }

    override suspend fun deleteTask(id: Long) {
        tasks.value = tasks.value.filterNot { it.id == id }
        logs.value = logs.value.filterNot { it.careTaskId == id }
    }

    override fun getLogsForTask(careTaskId: Long): Flow<List<CareTaskLog>> =
        logs.map { list -> list.filter { it.careTaskId == careTaskId }.sortedByDescending { it.scheduledTimeMs } }

    override suspend fun getLogsForToday(startMs: Long): List<CareTaskLog> =
        logs.value.filter { it.scheduledTimeMs >= startMs && it.scheduledTimeMs < startMs + DAY_MS }

    override suspend fun getLogsForRange(startMs: Long, endMs: Long): List<CareTaskLog> =
        logs.value.filter { it.scheduledTimeMs in startMs..endMs }

    override suspend fun getLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long): CareTaskLog? =
        logs.value.firstOrNull { it.careTaskId == careTaskId && it.scheduledTimeMs == scheduledTimeMs }

    override suspend fun upsertLog(log: CareTaskLog): Long {
        val existing = logs.value.firstOrNull {
            it.careTaskId == log.careTaskId && it.scheduledTimeMs == log.scheduledTimeMs
        }
        return if (existing == null) {
            val id = nextLogId++
            logs.value = logs.value + log.copy(id = id)
            id
        } else {
            logs.value = logs.value.map { if (it.id == existing.id) log.copy(id = existing.id) else it }
            existing.id
        }
    }

    override suspend fun updateLog(log: CareTaskLog) {
        logs.value = logs.value.map { if (it.id == log.id) log else it }
    }

    override suspend fun deleteLog(log: CareTaskLog) {
        logs.value = logs.value.filterNot { it.id == log.id }
    }

    override suspend fun deleteLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long) {
        logs.value = logs.value.filterNot { it.careTaskId == careTaskId && it.scheduledTimeMs == scheduledTimeMs }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
