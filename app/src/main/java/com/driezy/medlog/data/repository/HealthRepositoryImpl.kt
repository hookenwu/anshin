package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.HealthRecordDao
import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class HealthRepositoryImpl @Inject constructor(
    private val dao: HealthRecordDao,
    private val activeRecipient: ActiveRecipientStore,
) : HealthRepository {

    private fun scoped(block: (Long) -> Flow<List<HealthRecord>>): Flow<List<HealthRecord>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入健康记录" }
        return recipientId
    }

    override fun getAllRecords(): Flow<List<HealthRecord>> = scoped { dao.getAllRecords(it) }

    override fun getRecordsByType(type: String): Flow<List<HealthRecord>> = scoped { dao.getRecordsByType(it, type) }

    override fun getRecordsInRange(from: Long, to: Long): Flow<List<HealthRecord>> =
        scoped { dao.getRecordsInRange(it, from, to) }

    override fun getRecordsByTypeInRange(type: String, from: Long, to: Long): Flow<List<HealthRecord>> =
        scoped { dao.getRecordsByTypeInRange(it, type, from, to) }

    override fun getLatestRecordPerType(): Flow<List<HealthRecord>> = scoped { dao.getLatestRecordPerType(it) }

    override suspend fun hasSourceCacheKey(sourceCacheKey: String): Boolean =
        dao.hasSourceCacheKey(requireRecipientId(), sourceCacheKey)

    override suspend fun addRecord(record: HealthRecord): Long = dao.insert(
        if (record.careRecipientId == ActiveRecipientStore.NO_RECIPIENT) {
            record.copy(careRecipientId = requireRecipientId())
        } else {
            record
        },
    )

    /**
     * 编辑沿用原归属：调用方重建实体时可能没带 `careRecipientId`（默认 0），
     * 这里以库中原行的归属为准，避免把记录改成"无归属"而从所有成员的视图里消失。
     */
    override suspend fun updateRecord(record: HealthRecord) {
        val owner = dao.getById(record.id)?.careRecipientId ?: record.careRecipientId
        dao.update(if (owner == record.careRecipientId) record else record.copy(careRecipientId = owner))
    }

    override suspend fun deleteRecord(record: HealthRecord) = dao.delete(record)
}
