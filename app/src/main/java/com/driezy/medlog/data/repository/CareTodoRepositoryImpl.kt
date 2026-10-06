package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareTodoDao
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待办仓库实现：成员维度收口，与 [CareTaskRepositoryImpl] 同一套约定。
 *
 * 状态流转在仓库层统一落库（[complete] / [cancel] / [reopen]），因为这里正是
 * 「幂等」与「撤销必须清空 closedAtMs」两条规则应该被收口的地方——散落到 UI/V M 会各写各的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CareTodoRepositoryImpl @Inject constructor(
    private val careTodoDao: CareTodoDao,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : CareTodoRepository {

    private fun scoped(block: (Long) -> Flow<List<CareTodo>>): Flow<List<CareTodo>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入待办数据" }
        return recipientId
    }

    private suspend fun currentOrNull(): Long? =
        activeRecipient.current().takeIf { it != ActiveRecipientStore.NO_RECIPIENT }

    override fun getOpenTodos(): Flow<List<CareTodo>> = scoped { careTodoDao.getOpenTodos(it) }

    override fun getHistoryTodos(): Flow<List<CareTodo>> = scoped { careTodoDao.getHistoryTodos(it) }

    override suspend fun getTodoById(id: Long): CareTodo? = careTodoDao.getById(id)

    override suspend fun getOpenTodosOnce(): List<CareTodo> =
        currentOrNull()?.let { careTodoDao.getOpenTodosOnce(it) } ?: emptyList()

    override suspend fun createTodo(
        title: String,
        dueAtMs: Long?,
        sourceType: String?,
        sourceId: Long?,
        sourceNote: String?,
    ): Long {
        val recipientId = requireRecipientId()
        return careTodoDao.insert(
            CareTodo(
                careRecipientId = recipientId,
                title = title.trim(),
                dueAtMs = dueAtMs,
                sourceType = sourceType,
                sourceId = sourceId,
                sourceNote = sourceNote,
                createdAtMs = clock.millis(),
            ),
        )
    }

    override suspend fun updateTodo(id: Long, title: String, dueAtMs: Long?, sourceNote: String?) {
        requireRecipientId()
        val existing = careTodoDao.getById(id) ?: return
        careTodoDao.update(existing.copy(title = title.trim(), dueAtMs = dueAtMs, sourceNote = sourceNote))
    }

    override suspend fun complete(id: Long) = close(id, CareTodoStatus.DONE, resolutionNote = null)

    override suspend fun cancel(id: Long, resolutionNote: String?) = close(id, CareTodoStatus.CANCELLED, resolutionNote)

    /**
     * 关闭为终态：重复点击同一状态是**幂等**的——不产生新行、不改 closedAtMs。
     * 从另一终态切换过来时才刷新关闭时间。
     */
    private suspend fun close(id: Long, target: String, resolutionNote: String?) {
        val existing = careTodoDao.getById(id)
        if (existing == null) {
            requireRecipientId()
            return
        }
        requireRecipientId()
        if (existing.status == target) return
        careTodoDao.update(
            existing.copy(
                status = target,
                closedAtMs = clock.millis(),
                resolutionNote = resolutionNote ?: existing.resolutionNote,
            ),
        )
    }

    override suspend fun reopen(id: Long) {
        val existing = careTodoDao.getById(id)
        if (existing == null) {
            requireRecipientId()
            return
        }
        requireRecipientId()
        if (existing.status == CareTodoStatus.OPEN) return
        // 撤销必须显式清空 closedAtMs，否则历史统计自相矛盾（docs/todos.md §2.3）。
        careTodoDao.update(existing.copy(status = CareTodoStatus.OPEN, closedAtMs = null))
    }
}
