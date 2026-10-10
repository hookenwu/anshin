package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareEventLog
import kotlinx.coroutines.flow.Flow

/**
 * 照护事件日志 DAO（首期 `kind = BOWEL`）。
 *
 * 成员维度约定与 [CareTodoDao] 一致：所有查询显式接收 recipientId，DAO 本身不认识「当前成员」。
 * 间隔是**派生值**（最新 `occurredAtMs` 读出后现算），因此 DAO 只提供「取最新一条」与「取全部」。
 */
@Dao
interface CareEventLogDao {

    /** 某成员某 kind 的全部日志，最新发生在前。 */
    @Query(
        "SELECT * FROM care_event_logs WHERE careRecipientId = :recipientId AND kind = :kind " +
            "ORDER BY occurredAtMs DESC, id DESC",
    )
    fun getLogs(recipientId: Long, kind: String): Flow<List<CareEventLog>>

    /** 某成员某 kind 的最新一条（锚点来源），无记录时发射 null。 */
    @Query(
        "SELECT * FROM care_event_logs WHERE careRecipientId = :recipientId AND kind = :kind " +
            "ORDER BY occurredAtMs DESC, id DESC LIMIT 1",
    )
    fun getNewest(recipientId: Long, kind: String): Flow<CareEventLog?>

    /** 一次性读取全部日志。 */
    @Query(
        "SELECT * FROM care_event_logs WHERE careRecipientId = :recipientId AND kind = :kind " +
            "ORDER BY occurredAtMs DESC, id DESC",
    )
    suspend fun getLogsOnce(recipientId: Long, kind: String): List<CareEventLog>

    /** 一次性读取最新一条（锚点）。 */
    @Query(
        "SELECT * FROM care_event_logs WHERE careRecipientId = :recipientId AND kind = :kind " +
            "ORDER BY occurredAtMs DESC, id DESC LIMIT 1",
    )
    suspend fun getNewestOnce(recipientId: Long, kind: String): CareEventLog?

    @Query("SELECT * FROM care_event_logs WHERE id = :id")
    suspend fun getById(id: Long): CareEventLog?

    @Insert
    suspend fun insert(log: CareEventLog): Long

    @Update
    suspend fun update(log: CareEventLog)

    @Delete
    suspend fun delete(log: CareEventLog)
}
