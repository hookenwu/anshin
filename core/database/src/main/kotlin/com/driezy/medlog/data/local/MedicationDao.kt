package com.driezy.medlog.data.local

import androidx.room.*
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
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

    /** Store the old schedule and replace the current one atomically. Stock-only writes use updateStock. */
    @Transaction
    suspend fun updatePlan(medication: Medication, changedAt: Long) {
        val previous = getMedicationById(medication.id) ?: return
        val previousPlan = previous.planRevision(changedAt)
        val nextPlan = medication.planRevision(changedAt).copy(effectiveFromMs = previous.planEffectiveFromMs)
        if (previousPlan != nextPlan) {
            if (changedAt > previous.planEffectiveFromMs) insertPlanRevision(previousPlan)
            updateMedication(medication.copy(planEffectiveFromMs = changedAt))
        } else {
            updateMedication(medication.copy(planEffectiveFromMs = previous.planEffectiveFromMs))
        }
    }

    /** Rebuilds a group of derived medication plans as one Room transaction. */
    @Transaction
    suspend fun updateMedications(medications: List<Medication>) {
        medications.forEach { updateMedication(it) }
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
