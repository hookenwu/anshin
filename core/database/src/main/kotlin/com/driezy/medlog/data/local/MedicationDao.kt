package com.driezy.medlog.data.local

import androidx.room.*
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.planRevision
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Query(
        """
        SELECT * FROM medications
        WHERE careRecipientId = :recipientId AND isArchived = 0
        ORDER BY isHighPriority DESC, reminderHour, reminderMinute
    """,
    )
    fun getActiveMedications(recipientId: Long): Flow<List<Medication>>

    @Query(
        "SELECT * FROM medications WHERE careRecipientId = :recipientId " +
            "ORDER BY isHighPriority DESC, name",
    )
    fun getAllMedications(recipientId: Long): Flow<List<Medication>>

    @Query("SELECT * FROM medications WHERE careRecipientId = :recipientId AND isArchived = 1 ORDER BY name")
    fun getArchivedMedications(recipientId: Long): Flow<List<Medication>>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getMedicationById(id: Long): Medication?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: Medication): Long

    @Query("SELECT LOWER(TRIM(name)) FROM medications WHERE careRecipientId = :recipientId AND isArchived = 0")
    suspend fun getNormalizedActiveNames(recipientId: Long): List<String>

    @Query("DELETE FROM medications WHERE careRecipientId = :recipientId AND isArchived = 0")
    suspend fun deleteActiveMedications(recipientId: Long)

    @Transaction
    suspend fun mergeMedicationsByName(recipientId: Long, medications: List<Medication>) {
        val names = getNormalizedActiveNames(recipientId).toMutableSet()
        medications.forEach { medication ->
            val normalizedName = medication.name.trim().lowercase()
            if (names.add(normalizedName)) insertMedication(medication.copy(id = 0, careRecipientId = recipientId))
        }
    }

    @Transaction
    suspend fun replaceActiveMedications(recipientId: Long, medications: List<Medication>) {
        deleteActiveMedications(recipientId)
        medications.forEach { insertMedication(it.copy(id = 0, careRecipientId = recipientId)) }
    }

    @Update
    suspend fun updateMedication(medication: Medication)

    @Query(
        """
        SELECT medication_plan_revisions.* FROM medication_plan_revisions
        INNER JOIN medications ON medications.id = medication_plan_revisions.medicationId
        WHERE medications.careRecipientId = :recipientId
        ORDER BY medication_plan_revisions.effectiveFromMs
    """,
    )
    fun observePlanRevisions(recipientId: Long): Flow<List<MedicationPlanRevision>>

    @Insert
    suspend fun insertPlanRevision(revision: MedicationPlanRevision)

    /**
     * 计划写入的**唯一**入口（docs/medication-time-offset.md §3.2 三分支）：
     *
     * 1. 计划字段逐字相同 ⇒ 原地写整行（非计划列照常落库）、返回 [PlanWriteResult.NoOp]、
     *    **不建修订、边界不动** —— 只有这种原地替换是合法的；
     * 2. 内容变化且 `changedAt` **严格越过**当前边界 ⇒ 插旧计划（`until = changedAt`）+ 边界前移到
     *    `changedAt`、返回 [PlanWriteResult.Advanced]；
     * 3. 其余（同毫秒重复写、时钟回拨）⇒ 抛 [PlanBoundaryNotAdvancedException]，**零写入**。
     *
     * 旧实现在分支 3 里"跳过插入但把边界改写成 `changedAt`"，会让上一条修订的 `until` 大于新边界
     * （区间重叠 ⇒ 投影/依从重复计数），或让前一段变成零长度（历史被吃掉）。现在两者都不可能发生。
     *
     * 修订行**只插不改**（本 DAO 没有 UPDATE/DELETE 修订的语句），边界只增不减 ⇒
     * 修订区间是 `[first.from, ∞)` 的一个划分（I1–I3）。
     * 只有库存变化走 [updateStock]，不经过这里（§3.1）。
     */
    @Transaction
    suspend fun updatePlan(medication: Medication, changedAt: Long): PlanWriteResult {
        val previous = getMedicationById(medication.id) ?: return PlanWriteResult.NoOp
        val previousPlan = previous.planRevision(changedAt)
        val nextPlan = medication.planRevision(changedAt).copy(effectiveFromMs = previous.planEffectiveFromMs)
        if (previousPlan == nextPlan) {
            updateMedication(medication.copy(planEffectiveFromMs = previous.planEffectiveFromMs))
            return PlanWriteResult.NoOp
        }
        if (changedAt <= previous.planEffectiveFromMs) {
            throw PlanBoundaryNotAdvancedException(
                medicationId = medication.id,
                boundaryMs = previous.planEffectiveFromMs,
                requestedMs = changedAt,
            )
        }
        insertPlanRevision(previousPlan)
        updateMedication(medication.copy(planEffectiveFromMs = changedAt))
        return PlanWriteResult.Advanced
    }

    @Delete
    suspend fun deleteMedication(medication: Medication)

    @Query("UPDATE medications SET isArchived = 1 WHERE id = :id AND careRecipientId = :recipientId")
    suspend fun archiveMedication(id: Long, recipientId: Long)

    @Query("UPDATE medications SET isArchived = 0 WHERE id = :id AND careRecipientId = :recipientId")
    suspend fun unarchiveMedication(id: Long, recipientId: Long)

    @Query("UPDATE medications SET stock = :newStock WHERE id = :id AND careRecipientId = :recipientId")
    suspend fun updateStock(id: Long, recipientId: Long, newStock: Double)

    /** Widget 专用：一次性查询某成员的全部在用药（不返回 Flow） */
    @Query(
        "SELECT * FROM medications WHERE careRecipientId = :recipientId AND isArchived = 0 " +
            "ORDER BY isHighPriority DESC, name",
    )
    suspend fun getAllMedicationsOnce(recipientId: Long): List<Medication>

    /** 含已归档的整份清单：重排时用于清理归档药品的残留通知/闹钟。 */
    @Query("SELECT * FROM medications WHERE careRecipientId = :recipientId ORDER BY name")
    suspend fun getAllMedicationsIncludingArchivedOnce(recipientId: Long): List<Medication>
}
