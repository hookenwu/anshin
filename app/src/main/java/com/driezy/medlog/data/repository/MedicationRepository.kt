package com.driezy.medlog.data.repository

import com.driezy.medlog.capability.reminders.application.PlanRoutineResult
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.RoutineSchedule
import kotlinx.coroutines.flow.Flow

/**
 * 药品配置领域的唯一真实来源（SSOT）。
 * 职责：药品 CRUD + 归档 + 库存；不涉及日志（SRP）。
 */
interface MedicationRepository {
    fun getActiveMedications(): Flow<List<Medication>>
    fun getArchivedMedications(): Flow<List<Medication>>
    fun getAllMedications(): Flow<List<Medication>>

    /** 指定成员的活跃（未归档）药品；供按成员重排提醒等"非当前成员"场景使用。 */
    suspend fun getMedicationsFor(recipientId: Long): List<Medication>

    /** 指定成员的整份清单（含已归档），用于清理归档药品的残留提醒。 */
    suspend fun getAllMedicationsFor(recipientId: Long): List<Medication>

    fun observePlanRevisions(): Flow<List<MedicationPlanRevision>>

    suspend fun getMedicationById(id: Long): Medication?
    suspend fun addMedication(medication: Medication): Long
    suspend fun updateMedication(medication: Medication)

    /**
     * 作息重算的**唯一**批量写入口（docs/medication-time-offset.md §3.5）：读快照、逐药 `gatedFor`、
     * `updatePlan` 全在**同一个事务**里，一个 `changedAt` 全批共用。
     *
     * 任一药的偏移非法 ⇒ 收集齐后抛 `RoutineResyncConflictException`（抛出点在事务内 ⇒ 整批回滚、
     * 零部分写入）；`PlanBoundaryNotAdvancedException` 与任何其它异常原样穿出，同样回滚。**不吞异常**。
     *
     * @param zeroOffsetFor 仅「将偏移归零并继续」这类用户显式动作传入；其余调用方传空集。
     */
    suspend fun planForRoutine(schedule: RoutineSchedule, zeroOffsetFor: Set<Long> = emptySet()): PlanRoutineResult
    suspend fun mergeMedicationsByName(medications: List<Medication>)
    suspend fun replaceActiveMedications(medications: List<Medication>)
    suspend fun deleteMedication(medication: Medication)
    suspend fun archiveMedication(id: Long)
    suspend fun unarchiveMedication(id: Long)

    /**
     * **直写**派生缓存列 `medications.isArchived`（不改计划链、不新建计划段），
     * 由状态写入 / 自动核对在同事务内调用（docs/medication-status-spec.md §2.A.3 / §8.1）。
     */
    suspend fun setArchivedCache(id: Long, archived: Boolean)
    suspend fun updateStock(id: Long, newStock: Double)

    /** Widget / 一次性读取活跃药品列表（非 Flow），用于 Glance Widget 刷新 */
    suspend fun getActiveOnce(): List<Medication>
}
