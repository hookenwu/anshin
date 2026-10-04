package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareTaskLog
import kotlinx.coroutines.flow.Flow

/**
 * 照护事项执行记录 DAO。
 *
 * 日志通过 `careTaskId` 继承所属成员，不冗余 careRecipientId；
 * 按成员过滤的查询通过 JOIN care_tasks 实现（与 MedicationLogDao 一致）。
 */
@Dao
interface CareTaskLogDao {

    @Query(
        """
        SELECT * FROM care_task_logs
        WHERE careTaskId = :careTaskId
        ORDER BY scheduledTimeMs DESC
        LIMIT :limit
        """,
    )
    fun getLogsForTask(careTaskId: Long, limit: Int = 60): Flow<List<CareTaskLog>>

    @Query(
        """
        SELECT care_task_logs.* FROM care_task_logs
        INNER JOIN care_tasks ON care_tasks.id = care_task_logs.careTaskId
        WHERE care_tasks.careRecipientId = :recipientId
          AND care_task_logs.scheduledTimeMs BETWEEN :startMs AND :endMs
        ORDER BY care_task_logs.scheduledTimeMs ASC
        """,
    )
    fun getLogsForDateRange(recipientId: Long, startMs: Long, endMs: Long): Flow<List<CareTaskLog>>

    /** 今日页/小组件：一次性查询某成员从 startMs 起 24 小时内的日志。 */
    @Query(
        """
        SELECT care_task_logs.* FROM care_task_logs
        INNER JOIN care_tasks ON care_tasks.id = care_task_logs.careTaskId
        WHERE care_tasks.careRecipientId = :recipientId
          AND care_task_logs.scheduledTimeMs >= :startMs
          AND care_task_logs.scheduledTimeMs < :startMs + 86400000
        ORDER BY care_task_logs.scheduledTimeMs ASC
        """,
    )
    suspend fun getLogsForDateOnce(recipientId: Long, startMs: Long): List<CareTaskLog>

    @Query(
        """
        SELECT COUNT(*) FROM care_task_logs
        INNER JOIN care_tasks ON care_tasks.id = care_task_logs.careTaskId
        WHERE care_tasks.careRecipientId = :recipientId
          AND care_task_logs.status = 'DONE'
          AND care_task_logs.scheduledTimeMs BETWEEN :startMs AND :endMs
        """,
    )
    fun getDoneCountForDateRange(recipientId: Long, startMs: Long, endMs: Long): Flow<Int>

    @Query(
        "SELECT * FROM care_task_logs WHERE careTaskId = :careTaskId AND scheduledTimeMs = :scheduledTimeMs LIMIT 1",
    )
    suspend fun getLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long): CareTaskLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CareTaskLog): Long

    @Update
    suspend fun updateLog(log: CareTaskLog)

    @Delete
    suspend fun deleteLog(log: CareTaskLog)

    @Query("DELETE FROM care_task_logs WHERE careTaskId = :careTaskId AND scheduledTimeMs = :scheduledTimeMs")
    suspend fun deleteLogForScheduledTime(careTaskId: Long, scheduledTimeMs: Long)

    @Query("DELETE FROM care_task_logs WHERE careTaskId = :careTaskId")
    suspend fun deleteLogsForTask(careTaskId: Long)
}
