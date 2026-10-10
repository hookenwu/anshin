package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareEventLogDao
import com.driezy.medlog.data.model.CareEventLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照护事件仓库实现：成员维度收口，与 [CareTodoRepositoryImpl] 同一套约定。
 *
 * `NO_RECIPIENT`：读不发射（flow 等价空、一次性读返回空集合/null）、写抛错。
 * 写入（record/edit/delete）**不触发任何通知**——通知只由既有周期 worker/接收器驱动（R9/R11）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CareEventRepositoryImpl @Inject constructor(
    private val careEventLogDao: CareEventLogDao,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : CareEventRepository {

    private fun scoped(block: (Long) -> Flow<List<CareEventLog>>): Flow<List<CareEventLog>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入照护事件数据" }
        return recipientId
    }

    private suspend fun currentOrNull(): Long? =
        activeRecipient.current().takeIf { it != ActiveRecipientStore.NO_RECIPIENT }

    override fun getLogs(kind: String): Flow<List<CareEventLog>> = scoped { careEventLogDao.getLogs(it, kind) }

    override fun getNewest(kind: String): Flow<CareEventLog?> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId ==
                ActiveRecipientStore.NO_RECIPIENT
            ) {
                flowOf(null)
            } else {
                careEventLogDao.getNewest(recipientId, kind)
            }
        }

    override suspend fun getLogsOnce(kind: String): List<CareEventLog> =
        currentOrNull()?.let { careEventLogDao.getLogsOnce(it, kind) } ?: emptyList()

    override suspend fun getNewestOnce(kind: String): CareEventLog? =
        currentOrNull()?.let { careEventLogDao.getNewestOnce(it, kind) }

    override suspend fun record(occurredAtMs: Long?, note: String?, kind: String): Long {
        val recipientId = requireRecipientId()
        val now = clock.millis()
        return careEventLogDao.insert(
            CareEventLog(
                careRecipientId = recipientId,
                kind = kind,
                occurredAtMs = occurredAtMs ?: now,
                note = note.normalizedNote(),
                createdAtMs = now,
            ),
        )
    }

    override suspend fun edit(id: Long, occurredAtMs: Long, note: String?) {
        requireRecipientId()
        val existing = careEventLogDao.getById(id) ?: return
        careEventLogDao.update(
            existing.copy(
                occurredAtMs = occurredAtMs,
                note = note.normalizedNote(),
                updatedAtMs = clock.millis(),
            ),
        )
    }

    override suspend fun delete(id: Long) {
        requireRecipientId()
        val existing = careEventLogDao.getById(id) ?: return
        careEventLogDao.delete(existing)
    }

    private fun String?.normalizedNote(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
