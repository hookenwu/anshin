package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareTodoDao
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 待办 DAO 的内存假件：成员作用域不在这里过滤（由仓库负责），只按 recipientId/status
 * 做与真 DAO 同构的查询，便于在 JVM 上验证仓库的成员收口与状态流转。
 */
class FakeCareTodoDao : CareTodoDao {

    private val rows = MutableStateFlow<List<CareTodo>>(emptyList())
    private var nextId = 1L

    fun seed(todo: CareTodo): Long {
        val id = if (todo.id != 0L) todo.id else nextId++
        rows.value = rows.value + todo.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    fun stored(): List<CareTodo> = rows.value

    fun storedById(id: Long): CareTodo? = rows.value.firstOrNull { it.id == id }

    override fun getOpenTodos(recipientId: Long): Flow<List<CareTodo>> = rows.map { list ->
        list.filter { it.careRecipientId == recipientId && it.status == CareTodoStatus.OPEN }
            .sortedBy { it.createdAtMs }
    }

    override fun getHistoryTodos(recipientId: Long): Flow<List<CareTodo>> = rows.map { list ->
        list.filter { it.careRecipientId == recipientId && it.status != CareTodoStatus.OPEN }
            .sortedByDescending { it.closedAtMs }
    }

    override suspend fun getOpenTodosOnce(recipientId: Long): List<CareTodo> =
        rows.value.filter { it.careRecipientId == recipientId && it.status == CareTodoStatus.OPEN }

    override suspend fun getById(id: Long): CareTodo? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(todo: CareTodo): Long {
        val id = nextId++
        rows.value = rows.value + todo.copy(id = id)
        return id
    }

    override suspend fun update(todo: CareTodo) {
        rows.value = rows.value.map { if (it.id == todo.id) todo else it }
    }
}
