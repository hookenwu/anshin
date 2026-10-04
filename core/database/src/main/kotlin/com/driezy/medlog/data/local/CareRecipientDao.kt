package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareRecipient
import kotlinx.coroutines.flow.Flow

@Dao
interface CareRecipientDao {

    @Query("SELECT * FROM care_recipients ORDER BY createdAtMs, id")
    fun observeAll(): Flow<List<CareRecipient>>

    @Query("SELECT * FROM care_recipients ORDER BY createdAtMs, id")
    suspend fun getAll(): List<CareRecipient>

    @Query("SELECT * FROM care_recipients WHERE id = :id")
    suspend fun getById(id: Long): CareRecipient?

    @Query("SELECT * FROM care_recipients WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): CareRecipient?

    @Query("SELECT COUNT(*) FROM care_recipients")
    suspend fun count(): Int

    @Insert
    suspend fun insert(recipient: CareRecipient): Long

    @Update
    suspend fun update(recipient: CareRecipient)

    /** 级联删除该成员的全部数据（medications → logs / plan revisions，以及症状与健康记录）。 */
    @Query("DELETE FROM care_recipients WHERE id = :id")
    suspend fun deleteById(id: Long)
}
