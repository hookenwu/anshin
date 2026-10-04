package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareRecipientDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CareRecipientRepositoryImpl @Inject constructor(
    private val dao: CareRecipientDao,
    private val activeRecipient: ActiveRecipientStore,
    private val transactions: TransactionRunner,
) : CareRecipientRepository {

    override fun observeRecipients(): Flow<List<CareRecipient>> = dao.observeAll()

    override suspend fun getRecipients(): List<CareRecipient> = dao.getAll()

    override suspend fun getById(id: Long): CareRecipient? = dao.getById(id)

    override suspend fun create(displayName: String): Long = transactions.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            CareRecipient(
                uuid = CareRecipient.newUuid(),
                displayName = displayName.trim(),
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        if (activeRecipient.current() == ActiveRecipientStore.NO_RECIPIENT) {
            activeRecipient.set(id)
        }
        id
    }

    override suspend fun rename(id: Long, displayName: String): Boolean = transactions.withTransaction {
        val existing = dao.getById(id) ?: return@withTransaction false
        val name = displayName.trim()
        if (name.isEmpty()) return@withTransaction false
        dao.update(existing.copy(displayName = name, updatedAtMs = System.currentTimeMillis()))
        true
    }

    override suspend fun delete(id: Long) = transactions.withTransaction {
        dao.deleteById(id)
        if (activeRecipient.current() == id) {
            val remaining = dao.getAll().firstOrNull()
            activeRecipient.set(remaining?.id ?: ActiveRecipientStore.NO_RECIPIENT)
        }
        Unit
    }

    override fun observeActiveRecipientId(): Flow<Long> = activeRecipient.recipientId

    override suspend fun activeRecipient(): CareRecipient? {
        val id = activeRecipient.current()
        if (id == ActiveRecipientStore.NO_RECIPIENT) return null
        return dao.getById(id)
    }

    override suspend fun setActiveRecipient(id: Long) {
        if (id != ActiveRecipientStore.NO_RECIPIENT && dao.getById(id) == null) return
        activeRecipient.set(id)
    }
}
