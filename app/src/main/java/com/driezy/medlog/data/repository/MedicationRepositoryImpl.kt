package com.driezy.medlog.data.repository

import com.driezy.medlog.capability.reminders.application.PlanRoutineResult
import com.driezy.medlog.capability.reminders.application.planRoutineBatch
import com.driezy.medlog.data.local.MedicationDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.RoutineSchedule
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

    override suspend fun getMedicationsFor(recipientId: Long): List<Medication> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            emptyList()
        } else {
            medicationDao.getAllMedicationsOnce(recipientId)
        }

    override suspend fun getAllMedicationsFor(recipientId: Long): List<Medication> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            emptyList()
        } else {
            medicationDao.getAllMedicationsIncludingArchivedOnce(recipientId)
        }

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

    override suspend fun updateMedication(medication: Medication) {
        medicationDao.updatePlan(medication.stamped(), clock.millis())
    }

    /**
     * 作息重算的唯一批量入口：快照、逐药物化 + 判定、`updatePlan` 全在**同一个事务**里，
     * `changedAt` 只取一次 ⇒ N 味药同一个边界、N 条修订，或一行不写（§3.5）。
     *
     * 这里没有 `runCatching`：冲突由 [planRoutineBatch] 收集后整批抛出，其它异常原样穿出，
     * 让 Room 回滚整个批次并把失败交给调用方（设置页 / B3 的启动重算）。
     */
    override suspend fun planForRoutine(schedule: RoutineSchedule, zeroOffsetFor: Set<Long>): PlanRoutineResult =
        transactions.withTransaction {
            val recipientId = activeRecipient.current()
            val snapshot = if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
                emptyList()
            } else {
                medicationDao.getAllMedicationsOnce(recipientId)
            }
            val changedAt = clock.millis()
            planRoutineBatch(snapshot, schedule, changedAt, zeroOffsetFor) { materialized ->
                medicationDao.updatePlan(materialized, changedAt)
            }
        }

    override suspend fun mergeMedicationsByName(medications: List<Medication>) =
        medicationDao.mergeMedicationsByName(requireRecipientId(), medications)

    override suspend fun replaceActiveMedications(medications: List<Medication>) =
        medicationDao.replaceActiveMedications(requireRecipientId(), medications)

    override suspend fun deleteMedication(medication: Medication) = medicationDao.deleteMedication(medication)

    override suspend fun archiveMedication(id: Long) = setArchived(id, true)

    override suspend fun unarchiveMedication(id: Long) = setArchived(id, false)

    override suspend fun setArchivedCache(id: Long, archived: Boolean) {
        val owner = medicationDao.getMedicationById(id)?.careRecipientId ?: return
        if (owner == ActiveRecipientStore.NO_RECIPIENT) return
        if (archived) {
            medicationDao.archiveMedication(id, owner)
        } else {
            medicationDao.unarchiveMedication(id, owner)
        }
    }

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
