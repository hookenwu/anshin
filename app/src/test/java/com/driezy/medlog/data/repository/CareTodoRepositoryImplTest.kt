package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 待办仓库成员收口与状态机落库规则（docs/todos.md §2.3 / §2.5）。
 *
 * 这些是不依赖 Android 运行时的纯 JVM 断言，真正随 `:app:testDebugUnitTest` 执行。
 */
class CareTodoRepositoryImplTest {

    private val dao = FakeCareTodoDao()
    private val clock = MutableClock(Instant.ofEpochMilli(NOW))

    private fun repoFor(recipientId: Long): CareTodoRepositoryImpl {
        val store: ActiveRecipientStore = mock {
            on { this.recipientId } doReturn MutableStateFlow(recipientId)
            onBlocking { current() } doReturn recipientId
        }
        return CareTodoRepositoryImpl(dao, store, clock)
    }

    @Test
    fun `reads are scoped to the active recipient and empty without one`() = runBlocking {
        dao.seed(todo(recipientId = 1, title = "爸爸的待办"))
        dao.seed(todo(recipientId = 2, title = "妈妈的待办"))

        assertEquals(listOf("爸爸的待办"), repoFor(1).getOpenTodos().first().map { it.title })
        assertEquals(listOf("妈妈的待办"), repoFor(2).getOpenTodos().first().map { it.title })
        assertEquals(listOf("爸爸的待办"), repoFor(1).getOpenTodosOnce().map { it.title })

        // NO_RECIPIENT：读**不发射**（等价于空），不抛错——与 CareTaskRepositoryImpl 同一约定
        assertNull(withTimeoutOrNull(200L) { repoFor(ActiveRecipientStore.NO_RECIPIENT).getOpenTodos().first() })
        assertEquals(emptyList<CareTodo>(), repoFor(ActiveRecipientStore.NO_RECIPIENT).getOpenTodosOnce())
        assertNull(withTimeoutOrNull(200L) { repoFor(ActiveRecipientStore.NO_RECIPIENT).getHistoryTodos().first() })
    }

    @Test
    fun `writes throw without an active recipient`(): Unit = runBlocking {
        val none = repoFor(ActiveRecipientStore.NO_RECIPIENT)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.createTodo("待办") }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.complete(1L) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.cancel(1L) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.reopen(1L) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.updateTodo(1L, "改标题", null, null) }
        }
    }

    @Test
    fun `create stamps the active recipient trims the title and starts open`(): Unit = runBlocking {
        val id = repoFor(7L).createTodo(title = "  让护士看压疮风险  ", sourceType = "OBSERVATION", sourceNote = "护工：骶尾处发红")
        val stored = dao.storedById(id)!!
        assertEquals(7L, stored.careRecipientId)
        assertEquals("让护士看压疮风险", stored.title)
        assertEquals(CareTodoStatus.OPEN, stored.status)
        assertEquals(NOW, stored.createdAtMs)
        assertNull(stored.closedAtMs)
        assertEquals("OBSERVATION", stored.sourceType)
        assertEquals("护工：骶尾处发红", stored.sourceNote)
    }

    @Test
    fun `complete sets DONE and closedAt then repeated taps stay idempotent`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createTodo("待办")

        clock.now = Instant.ofEpochMilli(NOW + 1_000)
        repo.complete(id)
        assertEquals(CareTodoStatus.DONE, dao.storedById(id)!!.status)
        assertEquals(NOW + 1_000, dao.storedById(id)!!.closedAtMs)

        // 重复点击：同一状态不再改写关闭时间
        clock.now = Instant.ofEpochMilli(NOW + 9_000)
        repo.complete(id)
        assertEquals(NOW + 1_000, dao.storedById(id)!!.closedAtMs)
    }

    @Test
    fun `cancel sets CANCELLED with a note and repeated taps stay idempotent`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createTodo("待办")

        clock.now = Instant.ofEpochMilli(NOW + 2_000)
        repo.cancel(id, "误报")
        val cancelled = dao.storedById(id)!!
        assertEquals(CareTodoStatus.CANCELLED, cancelled.status)
        assertEquals(NOW + 2_000, cancelled.closedAtMs)
        assertEquals("误报", cancelled.resolutionNote)

        clock.now = Instant.ofEpochMilli(NOW + 8_000)
        repo.cancel(id)
        assertEquals(NOW + 2_000, dao.storedById(id)!!.closedAtMs)
    }

    @Test
    fun `reopen returns to OPEN and clears closedAt idempotently`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createTodo("待办")
        repo.complete(id)
        assertEquals(NOW, dao.storedById(id)!!.closedAtMs)

        repo.reopen(id)
        val reopened = dao.storedById(id)!!
        assertEquals(CareTodoStatus.OPEN, reopened.status)
        assertNull("撤销必须清空 closedAtMs", reopened.closedAtMs)

        // 再撤销（已是 OPEN）不改任何时间戳
        repo.reopen(id)
        assertNull(dao.storedById(id)!!.closedAtMs)
        assertEquals(NOW, dao.storedById(id)!!.createdAtMs)
    }

    @Test
    fun `update edits content but keeps identity status and timestamps`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createTodo("原标题")

        clock.now = Instant.ofEpochMilli(NOW + 5_000)
        repo.updateTodo(id, "  新标题  ", dueAtMs = NOW + 86_400_000, sourceNote = "来源备注")

        val stored = dao.storedById(id)!!
        assertEquals("新标题", stored.title)
        assertEquals(NOW + 86_400_000, stored.dueAtMs)
        assertEquals("来源备注", stored.sourceNote)
        assertEquals(1L, stored.careRecipientId)
        assertEquals(CareTodoStatus.OPEN, stored.status)
        assertEquals(NOW, stored.createdAtMs)
        assertNull(stored.closedAtMs)
    }

    private fun todo(recipientId: Long, title: String) = CareTodo(careRecipientId = recipientId, title = title)

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
