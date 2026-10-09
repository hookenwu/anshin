package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CarePerson
import kotlinx.coroutines.flow.Flow

/**
 * 人员档案 DAO（docs/care-people.md §1/§5）。
 *
 * 与 CareTodoDao 同一套成员维度约定：所有查询显式接收 recipientId，由仓库层注入「当前成员」，
 * DAO 本身不认识这一概念。人员查询**永远带 `careRecipientId`**（成员隔离）。
 */
@Dao
interface CarePersonDao {

    /** 当前成员的全部人员，按姓名排序（供管理面列表）。 */
    @Query(
        "SELECT * FROM care_people WHERE careRecipientId = :recipientId " +
            "ORDER BY name COLLATE NOCASE ASC, id ASC",
    )
    fun getPeople(recipientId: Long): Flow<List<CarePerson>>

    /** 选择器搜索：按姓名匹配（前缀/包含皆可），作用域为当前成员。 */
    @Query(
        "SELECT * FROM care_people WHERE careRecipientId = :recipientId AND name LIKE '%' || :query || '%' " +
            "ORDER BY name COLLATE NOCASE ASC, id ASC",
    )
    fun searchPeople(recipientId: Long, query: String): Flow<List<CarePerson>>

    @Query("SELECT * FROM care_people WHERE id = :id")
    suspend fun getById(id: Long): CarePerson?

    @Query("SELECT COUNT(*) FROM care_people WHERE id = :id AND careRecipientId = :recipientId")
    suspend fun countBelongingTo(id: Long, recipientId: Long): Int

    /** 删除前提示用：当前成员下引用该人员的笔记数（不阻断删除，docs/care-people.md §9.2）。 */
    @Query("SELECT COUNT(*) FROM care_notes WHERE careRecipientId = :recipientId AND attributionPersonId = :personId")
    suspend fun countNotesReferencing(recipientId: Long, personId: Long): Int

    @Insert
    suspend fun insert(person: CarePerson): Long

    @Update
    suspend fun update(person: CarePerson)

    @Query("DELETE FROM care_people WHERE id = :id")
    suspend fun deleteById(id: Long)
}
