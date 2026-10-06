package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 待办仓储的内存假件：成员作用域在单测里不参与（VM/契约测试只关心 state 与命令分派），
 * 因此所有读直接返回当前集合，写就地更新并分配自增 id；[clockMs] 用于让 created/closed 可预测。
 */
class FakeCareTodoRepository : CareTodoRepository {

    private val todos = MutableStateFlow<List<CareTodo>>(emptyList())
    private var nextId = 1L

    /** 决定写入时间戳的「当前时间」；测试可显式设置。 */
    var clockMs: Long = 1_000L

    /** 打开后，所有写操作抛错，用于验证 UI/VM 的错误分支。 */
    var failWrites: Boolean = false

    fun seed(todo: CareTodo): Long {
        val id = if (todo.id != 0L) todo.id else nextId++
        todos.value = todos.value + todo.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    fun stored(): List<CareTodo> = todos.value

    fun storedById(id: Long): CareTodo? = todos.value.firstOrNull { it.id == id }

    override fun getOpenTodos(): Flow<List<CareTodo>> =
        todos.map { list -> list.filter { it.status == CareTodoStatus.OPEN } }

    override fun getHistoryTodos(): Flow<List<CareTodo>> =
        todos.map { list -> list.filter { it.status != CareTodoStatus.OPEN } }

    override suspend fun getTodoById(id: Long): CareTodo? = storedById(id)

    override suspend fun getOpenTodosOnce(): List<CareTodo> = todos.value.filter { it.status == CareTodoStatus.OPEN }

    override suspend fun createTodo(
        title: String,
        dueAtMs: Long?,
        sourceType: String?,
        sourceId: Long?,
        sourceNote: String?,
    ): Long {
        maybeFail()
        return seed(
            CareTodo(
                careRecipientId = 1L,
                title = title.trim(),
                dueAtMs = dueAtMs,
                sourceType = sourceType,
                sourceId = sourceId,
                sourceNote = sourceNote,
                createdAtMs = clockMs,
            ),
        )
    }

    override suspend fun updateTodo(id: Long, title: String, dueAtMs: Long?, sourceNote: String?) {
        maybeFail()
        replace(id) { it.copy(title = title.trim(), dueAtMs = dueAtMs, sourceNote = sourceNote) }
    }

    override suspend fun complete(id: Long) = close(id, CareTodoStatus.DONE, null)

    override suspend fun cancel(id: Long, resolutionNote: String?) = close(id, CareTodoStatus.CANCELLED, resolutionNote)

    override suspend fun reopen(id: Long) {
        maybeFail()
        replace(id) {
            if (it.status ==
                CareTodoStatus.OPEN
            ) {
                it
            } else {
                it.copy(status = CareTodoStatus.OPEN, closedAtMs = null)
            }
        }
    }

    private fun close(id: Long, target: String, resolutionNote: String?) {
        maybeFail()
        replace(id) {
            if (it.status == target) {
                it
            } else {
                it.copy(status = target, closedAtMs = clockMs, resolutionNote = resolutionNote ?: it.resolutionNote)
            }
        }
    }

    private fun replace(id: Long, block: (CareTodo) -> CareTodo) {
        todos.value = todos.value.map { if (it.id == id) block(it) else it }
    }

    private fun maybeFail() {
        if (failWrites) error("write failed")
    }
}
