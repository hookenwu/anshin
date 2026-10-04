package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.SymptomLogDao
import com.driezy.medlog.data.model.SymptomLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SymptomRepositoryImpl @Inject constructor(
    private val dao: SymptomLogDao,
    private val activeRecipient: ActiveRecipientStore,
) : SymptomRepository {

    private fun scoped(block: (Long) -> Flow<List<SymptomLog>>): Flow<List<SymptomLog>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入症状日记" }
        return recipientId
    }

    override fun getAllLogs(): Flow<List<SymptomLog>> = scoped { dao.getAllLogs(it) }

    override fun getLogsForDateRange(startMs: Long, endMs: Long): Flow<List<SymptomLog>> =
        scoped { dao.getLogsForDateRange(it, startMs, endMs) }

    override fun getLogsForMedication(medId: Long): Flow<List<SymptomLog>> = dao.getLogsForMedication(medId)

    override suspend fun insert(log: SymptomLog): Long = dao.insert(
        if (log.careRecipientId == ActiveRecipientStore.NO_RECIPIENT) {
            log.copy(careRecipientId = requireRecipientId())
        } else {
            log
        },
    )

    /** 编辑沿用原归属（同健康记录：避免调用方漏带 careRecipientId 时把行写成 0 而丢归属）。 */
    override suspend fun update(log: SymptomLog) {
        val owner = dao.getById(log.id)?.careRecipientId ?: log.careRecipientId
        dao.update(if (owner == log.careRecipientId) log else log.copy(careRecipientId = owner))
    }

    override suspend fun delete(log: SymptomLog) = dao.delete(log)

    /** 删除按行归属生效，避免成员切换后误删他人记录。 */
    override suspend fun deleteById(id: Long) {
        val owner = dao.getById(id)?.careRecipientId ?: return
        if (owner == ActiveRecipientStore.NO_RECIPIENT) return
        dao.deleteById(id, owner)
    }
}
