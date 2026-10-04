package com.driezy.medlog.data.local

import androidx.room.*
import com.driezy.medlog.data.model.MedicationLog
import kotlinx.coroutines.flow.Flow

/**
 * 服药日志 DAO。
 *
 * 日志通过 `medicationId` 继承所属成员，不冗余 `careRecipientId`；
 * 按时间范围/数量的查询通过 JOIN medications 按成员过滤。
 */
@Dao
interface MedicationLogDao {

    @Query(
        """
        SELECT medication_logs.* FROM medication_logs
        INNER JOIN medications ON medications.id = medication_logs.medicationId
        WHERE medications.careRecipientId = :recipientId
          AND medication_logs.scheduledTimeMs BETWEEN :startMs AND :endMs
        ORDER BY medication_logs.scheduledTimeMs ASC
        """,
    )
    fun getLogsForDateRange(recipientId: Long, startMs: Long, endMs: Long): Flow<List<MedicationLog>>

    @Query(
        """
        SELECT * FROM medication_logs
        WHERE medicationId = :medicationId
        ORDER BY scheduledTimeMs DESC
        LIMIT :limit
        """,
    )
    fun getLogsForMedication(medicationId: Long, limit: Int = 60): Flow<List<MedicationLog>>

    @Query(
        """
        SELECT * FROM medication_logs
        WHERE medicationId = :medicationId
          AND scheduledTimeMs BETWEEN :startMs AND :endMs
        """,
    )
    suspend fun getLogForMedicationAndDate(medicationId: Long, startMs: Long, endMs: Long): MedicationLog?

    @Query(
        "SELECT * FROM medication_logs WHERE medicationId = :medicationId AND scheduledTimeMs = :scheduledTimeMs LIMIT 1",
    )
    suspend fun getLogForScheduledTime(medicationId: Long, scheduledTimeMs: Long): MedicationLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: MedicationLog): Long

    @Update
    suspend fun updateLog(log: MedicationLog)

    @Delete
    suspend fun deleteLog(log: MedicationLog)

    @Query(
        "DELETE FROM medication_logs WHERE medicationId = :medicationId AND scheduledTimeMs BETWEEN :startMs AND :endMs",
    )
    suspend fun deleteLogsForMedicationAndDate(medicationId: Long, startMs: Long, endMs: Long)

    @Query("DELETE FROM medication_logs WHERE medicationId = :medicationId AND scheduledTimeMs = :scheduledTimeMs")
    suspend fun deleteLogForScheduledTime(medicationId: Long, scheduledTimeMs: Long)

    @Query(
        """
        SELECT COUNT(*) FROM medication_logs
        INNER JOIN medications ON medications.id = medication_logs.medicationId
        WHERE medications.careRecipientId = :recipientId
          AND medication_logs.status = 'TAKEN'
          AND medication_logs.scheduledTimeMs BETWEEN :startMs AND :endMs
        """,
    )
    fun getTakenCountForDateRange(recipientId: Long, startMs: Long, endMs: Long): Flow<Int>

    /** Widget 专用：一次性查询某成员某天开始后的所有日志 */
    @Query(
        """
        SELECT medication_logs.* FROM medication_logs
        INNER JOIN medications ON medications.id = medication_logs.medicationId
        WHERE medications.careRecipientId = :recipientId
          AND medication_logs.scheduledTimeMs >= :startMs
          AND medication_logs.scheduledTimeMs < :startMs + 86400000
        """,
    )
    suspend fun getLogsForDateOnce(recipientId: Long, startMs: Long): List<MedicationLog>

    /** Widget / Streak 专用：一次性查询某成员时间范围内的所有日志 */
    @Query(
        """
        SELECT medication_logs.* FROM medication_logs
        INNER JOIN medications ON medications.id = medication_logs.medicationId
        WHERE medications.careRecipientId = :recipientId
          AND medication_logs.scheduledTimeMs BETWEEN :startMs AND :endMs
        """,
    )
    suspend fun getLogsForRangeOnce(recipientId: Long, startMs: Long, endMs: Long): List<MedicationLog>
}
