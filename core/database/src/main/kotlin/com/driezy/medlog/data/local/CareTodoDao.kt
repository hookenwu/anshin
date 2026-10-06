package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareTodo
import kotlinx.coroutines.flow.Flow

/**
 * 待办 DAO。
 *
 * 与 CareTaskDao 同一套成员维度约定：所有查询显式接收 recipientId，由仓库层注入
 * 「当前成员」，DAO 本身不认识这一概念。状态流转（完成/取消/撤销）由仓库改写整行后
 * 走 [update]，不在 DAO 里堆散装 UPDATE，便于把幂等与 `closedAtMs` 清空规则收在一处。
 */
@Dao
interface CareTodoDao {

    /** 未闭环待办（首页区块 / 列表「进行中」）。 */
    @Query(
        "SELECT * FROM care_todos WHERE careRecipientId = :recipientId AND status = 'OPEN' " +
            "ORDER BY createdAtMs ASC, id ASC",
    )
    fun getOpenTodos(recipientId: Long): Flow<List<CareTodo>>

    /** 历史待办（DONE + CANCELLED），最近关闭在前。 */
    @Query(
        "SELECT * FROM care_todos WHERE careRecipientId = :recipientId AND status != 'OPEN' " +
            "ORDER BY closedAtMs DESC, id DESC",
    )
    fun getHistoryTodos(recipientId: Long): Flow<List<CareTodo>>

    @Query(
        "SELECT * FROM care_todos WHERE careRecipientId = :recipientId AND status = 'OPEN' " +
            "ORDER BY createdAtMs ASC, id ASC",
    )
    suspend fun getOpenTodosOnce(recipientId: Long): List<CareTodo>

    @Query("SELECT * FROM care_todos WHERE id = :id")
    suspend fun getById(id: Long): CareTodo?

    @Insert
    suspend fun insert(todo: CareTodo): Long

    @Update
    suspend fun update(todo: CareTodo)
}
