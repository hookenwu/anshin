package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.MedicationDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 药品 SSOT 仓库。
 *
 * 所有读写都按"当前家庭成员"收口：查询按 careRecipientId 过滤，
 * 写入前绑定当前成员；未选择成员时读返回空、写直接报错。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MedicationRepositoryImpl @Inject constructor(
    private val medicationDao: MedicationDao,
    private val transactions: TransactionRunner,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : MedicationRepository {

    private fun scoped(block: (Long) -> Flow<List<Medication>>): Flow<List<Medication>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入用药数据" }
        return recipientId
    }

    /** 写入前绑定当前成员；已带成员的实体原样返回。 */
    private suspend fun Medication.stamped(): Medication =
        if (careRecipientId == ActiveRecipientStore.NO_RECIPIENT) copy(careRecipientId = requireRecipientId()) else this

    override fun getActiveMedications(): Flow<List<Medication>> = scoped { medicationDao.getActiveMedications(it) }

    override fun getArchivedMedications(): Flow<List<Medication>> = scoped { medicationDao.getArchivedMedications(it) }

    override fun getAllMedications(): Flow<List<Medication>> = scoped { medicationDao.getAllMedications(it) }

    override fun observePlanRevisions(): Flow<List<MedicationPlanRevision>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
                emptyFlow()
            } else {
                medicationDao.observePlanRevisions(recipientId)
            }
        }

    override suspend fun getMedicationById(id: Long): Medication? = medicationDao.getMedicationById(id)

    override suspend fun addMedication(medication: Medication): Long =
        medicationDao.insertMedication(medication.stamped())

    override suspend fun updateMedication(medication: Medication) =
        medicationDao.updatePlan(medication.stamped(), clock.millis())

    override suspend fun updateMedications(medications: List<Medication>) = transactions.withTransaction {
        val changedAt = clock.millis()
        medications.forEach { medicationDao.updatePlan(it.stamped(), changedAt) }
    }

    override suspend fun mergeMedicationsByName(medications: List<Medication>) =
        medicationDao.mergeMedicationsByName(requireRecipientId(), medications)

    override suspend fun replaceActiveMedications(medications: List<Medication>) =
        medicationDao.replaceActiveMedications(requireRecipientId(), medications)

    override suspend fun deleteMedication(medication: Medication) = medicationDao.deleteMedication(medication)

    override suspend fun archiveMedication(id: Long) = setArchived(id, true)

    override suspend fun unarchiveMedication(id: Long) = setArchived(id, false)

    private suspend fun setArchived(id: Long, archived: Boolean) = transactions.withTransaction {
        medicationDao.getMedicationById(id)?.let {
            medicationDao.updatePlan(it.copy(isArchived = archived), clock.millis())
        }
        Unit
    }

    /** 库存写入按行归属生效，避免成员切换竞态把库存写到别人名下。 */
    override suspend fun updateStock(id: Long, newStock: Double) {
        val owner = medicationDao.getMedicationById(id)?.careRecipientId ?: return
        if (owner == ActiveRecipientStore.NO_RECIPIENT) return
        medicationDao.updateStock(id, owner, newStock)
    }

    override suspend fun getActiveOnce(): List<Medication> {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return emptyList()
        return medicationDao.getAllMedicationsOnce(recipientId)
    }
}
