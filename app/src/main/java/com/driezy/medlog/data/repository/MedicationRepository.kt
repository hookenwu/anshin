package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
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
    suspend fun updateMedications(medications: List<Medication>)
    suspend fun mergeMedicationsByName(medications: List<Medication>)
    suspend fun replaceActiveMedications(medications: List<Medication>)
    suspend fun deleteMedication(medication: Medication)
    suspend fun archiveMedication(id: Long)
    suspend fun unarchiveMedication(id: Long)
    suspend fun updateStock(id: Long, newStock: Double)

    /** Widget / 一次性读取活跃药品列表（非 Flow），用于 Glance Widget 刷新 */
    suspend fun getActiveOnce(): List<Medication>
}
