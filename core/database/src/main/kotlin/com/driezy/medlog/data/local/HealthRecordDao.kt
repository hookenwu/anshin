package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.HealthRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: HealthRecord): Long

    @Update
    suspend fun update(record: HealthRecord)

    @Delete
    suspend fun delete(record: HealthRecord)

    /** 某成员的所有记录，按时间倒序 */
    @Query("SELECT * FROM health_records WHERE careRecipientId = :recipientId ORDER BY timestamp DESC")
    fun getAllRecords(recipientId: Long): Flow<List<HealthRecord>>

    /** 某成员指定类型的记录，按时间倒序 */
    @Query(
        "SELECT * FROM health_records WHERE careRecipientId = :recipientId AND type = :type " +
            "ORDER BY timestamp DESC",
    )
    fun getRecordsByType(recipientId: Long, type: String): Flow<List<HealthRecord>>

    /** 某成员指定时间范围内的记录（用于生成趋势图），按时间正序 */
    @Query(
        "SELECT * FROM health_records WHERE careRecipientId = :recipientId " +
            "AND timestamp >= :from AND timestamp <= :to ORDER BY timestamp ASC",
    )
    fun getRecordsInRange(recipientId: Long, from: Long, to: Long): Flow<List<HealthRecord>>

    /** 某成员指定类型在指定时间范围内的记录，按时间正序 */
    @Query(
        "SELECT * FROM health_records WHERE careRecipientId = :recipientId AND type = :type " +
            "AND timestamp >= :from AND timestamp <= :to ORDER BY timestamp ASC",
    )
    fun getRecordsByTypeInRange(recipientId: Long, type: String, from: Long, to: Long): Flow<List<HealthRecord>>

    /** 某成员每种类型的最新一条记录（用于主页快速展示；同毫秒时按 id 取最大保证确定） */
    @Query(
        "SELECT h.* FROM health_records h " +
            "WHERE h.careRecipientId = :recipientId AND h.id = (" +
            "SELECT h2.id FROM health_records h2 " +
            "WHERE h2.careRecipientId = :recipientId AND h2.type = h.type " +
            "ORDER BY h2.timestamp DESC, h2.id DESC LIMIT 1" +
            ")",
    )
    fun getLatestRecordPerType(recipientId: Long): Flow<List<HealthRecord>>

    @Query("SELECT * FROM health_records WHERE id = :id")
    suspend fun getById(id: Long): HealthRecord?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM health_records " +
            "WHERE careRecipientId = :recipientId AND sourceCacheKey = :sourceCacheKey LIMIT 1)",
    )
    suspend fun hasSourceCacheKey(recipientId: Long, sourceCacheKey: String): Boolean
}
