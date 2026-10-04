package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareTaskDao
import com.driezy.medlog.data.local.CareTaskLogDao
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照护事项仓库实现（成员维度收口，与 MedicationRepositoryImpl 同一套约定）。
 *
 * 日志通过 careTaskId 继承成员，写入前只校验"有当前成员"；
 * 单次读取在未选择成员时返回空集合而不是抛错（读操作保持平稳）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CareTaskRepositoryImpl @Inject constructor(
    private val careTaskDao: CareTaskDao,
    private val careTaskLogDao: CareTaskLogDao,
    private val activeRecipient: ActiveRecipientStore,
) : CareTaskRepository {

    private fun scoped(block: (Long) -> Flow<List<CareTask>>): Flow<List<CareTask>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private fun scopedLogs(block: (Long) -> Flow<List<CareTaskLog>>): Flow<List<CareTaskLog>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入照护事项数据" }
        return recipientId
    }

    private suspend fun currentOrNull(): Long? =
        activeRecipient.current().takeIf { it != ActiveRecipientStore.NO_RECIPIENT }

    /** 写入前绑定当前成员；已带成员的实体原样返回。 */
    private suspend fun CareTask.stamped(): CareTask = if (careRecipientId == ActiveRecipientStore.NO_RECIPIENT) {
        copy(careRecipientId = requireRecipientId())
    } else {
        this
    }

    override fun getActiveTasks(): Flow<List<CareTask>> = scoped { careTaskDao.getActiveTasks(it) }

    override fun getArchivedTasks(): Flow<List<CareTask>> = scoped { careTaskDao.getArchivedTasks(it) }

    override suspend fun getActiveTasksOnce(): List<CareTask> =
        currentOrNull()?.let { careTaskDao.getActiveTasksOnce(it) } ?: emptyList()

    override suspend fun getTasksFor(recipientId: Long): List<CareTask> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            emptyList()
        } else {
            careTaskDao.getActiveTasksOnce(recipientId)
        }

    override suspend fun getAllTasksFor(recipientId: Long): List<CareTask> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            emptyList()
        } else {
            careTaskDao.getAllTasksOnce(recipientId)
        }

    override suspend fun getTaskById(id: Long): CareTask? = careTaskDao.getById(id)

    override suspend fun addTask(task: CareTask): Long = careTaskDao.insert(task.stamped())

    override suspend fun updateTask(task: CareTask) = careTaskDao.update(task.stamped())

    override suspend fun setArchived(id: Long, archived: Boolean) {
        requireRecipientId()
        careTaskDao.setArchived(id, archived)
    }

    override suspend fun deleteTask(id: Long) {
        requireRecipientId()
        careTaskDao.deleteById(id)
    }

    override fun getLogsForTask(careTaskId: Long): Flow<List<CareTaskLog>> = careTaskLogDao.getLogsForTask(careTaskId)

    override suspend fun getLogsForToday(startMs: Long): List<CareTaskLog> =
        currentOrNull()?.let { careTaskLogDao.getLogsForDateOnce(it, startMs) } ?: emptyList()

    override suspend fun getLogsForRange(startMs: Long, endMs: Long): List<CareTaskLog> {
        val recipientId = currentOrNull() ?: return emptyList()
        return careTaskLogDao.getLogsForDateRange(recipientId, startMs, endMs).first()
    }

    override suspend fun getLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long): CareTaskLog? =
        careTaskLogDao.getLogForScheduledTime(careTaskId, scheduledTimeMs)

    override suspend fun upsertLog(log: CareTaskLog): Long {
        requireRecipientId()
        return careTaskLogDao.insertLog(log)
    }

    override suspend fun updateLog(log: CareTaskLog) {
        requireRecipientId()
        careTaskLogDao.updateLog(log)
    }

    override suspend fun deleteLog(log: CareTaskLog) {
        requireRecipientId()
        careTaskLogDao.deleteLog(log)
    }

    override suspend fun deleteLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long) {
        requireRecipientId()
        careTaskLogDao.deleteLogForScheduledTime(careTaskId, scheduledTimeMs)
    }
}
