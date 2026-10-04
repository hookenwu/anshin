package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareTask
import kotlinx.coroutines.flow.Flow

/**
 * 照护事项 DAO。
 *
 * 与 MedicationDao 同一套成员维度约定：所有查询显式接收 recipientId，
 * 由仓库层注入"当前成员"，DAO 本身不认识"当前成员"这一概念。
 */
@Dao
interface CareTaskDao {

    @Query("SELECT * FROM care_tasks WHERE careRecipientId = :recipientId AND isArchived = 0 ORDER BY title")
    fun getActiveTasks(recipientId: Long): Flow<List<CareTask>>

    @Query("SELECT * FROM care_tasks WHERE careRecipientId = :recipientId AND isArchived = 1 ORDER BY title")
    fun getArchivedTasks(recipientId: Long): Flow<List<CareTask>>

    @Query("SELECT * FROM care_tasks WHERE careRecipientId = :recipientId AND isArchived = 0 ORDER BY title")
    suspend fun getActiveTasksOnce(recipientId: Long): List<CareTask>

    /** 含已归档：重排提醒时用于清理归档事项的残留闹钟/通知。 */
    @Query("SELECT * FROM care_tasks WHERE careRecipientId = :recipientId ORDER BY title")
    suspend fun getAllTasksOnce(recipientId: Long): List<CareTask>

    @Query("SELECT * FROM care_tasks WHERE id = :id")
    suspend fun getById(id: Long): CareTask?

    @Query("SELECT COUNT(*) FROM care_tasks WHERE careRecipientId = :recipientId")
    suspend fun countForRecipient(recipientId: Long): Int

    @Insert
    suspend fun insert(task: CareTask): Long

    @Update
    suspend fun update(task: CareTask)

    @Query("UPDATE care_tasks SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM care_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
