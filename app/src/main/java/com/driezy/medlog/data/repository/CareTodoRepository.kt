package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareTodo
import kotlinx.coroutines.flow.Flow

/**
 * 待办事项 SSOT 仓库（docs/todos.md §2.5）。
 *
 * 与 CareTaskRepository 同一套成员维度约定：查询按「当前家庭成员」过滤，写入前绑定当前成员；
 * 未选择成员时读返回空、写直接报错。[complete] / [cancel] / [reopen] 是状态机的唯一落库入口，
 * 幂等且保证「撤销回 OPEN 必清空 closedAtMs」。
 */
interface CareTodoRepository {

    /** 未闭环待办（首页区块 / 列表「进行中」）。 */
    fun getOpenTodos(): Flow<List<CareTodo>>

    /** 历史待办（DONE + CANCELLED），最近关闭在前。 */
    fun getHistoryTodos(): Flow<List<CareTodo>>

    suspend fun getTodoById(id: Long): CareTodo?

    /** 当前成员的未闭环待办一次性读取（供契约/回归断言）。 */
    suspend fun getOpenTodosOnce(): List<CareTodo>

    /** 新建待办；标题必填，身份与 createdAt 由仓库盖章。 */
    suspend fun createTodo(
        title: String,
        dueAtMs: Long? = null,
        sourceType: String? = null,
        sourceId: Long? = null,
        sourceNote: String? = null,
    ): Long

    /** 编辑正文/截止/来源备注；身份字段与其他状态字段保持不变。 */
    suspend fun updateTodo(id: Long, title: String, dueAtMs: Long?, sourceNote: String?)

    /** 完成：status = DONE、closedAtMs = now；重复调用幂等。 */
    suspend fun complete(id: Long)

    /** 取消：status = CANCELLED、closedAtMs = now；重复调用幂等。 */
    suspend fun cancel(id: Long, resolutionNote: String? = null)

    /** 撤销/重开：status = OPEN 且 **closedAtMs = null**；重复调用幂等。 */
    suspend fun reopen(id: Long)
}
