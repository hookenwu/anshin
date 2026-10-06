package com.driezy.medlog.feature.medications.home

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 首页待办区块排序 / 封顶 / 空态规则（docs/todos.md §3）。纯 JVM 断言。
 *
 * 基准：2026-09-19 12:00 CST（Asia/Shanghai）。
 */
class HomeTodoBlockTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-19T04:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `orders overdue then due today then undated tying by createdAt`() {
        val overdue = todo(id = 1, createdAtMs = 5_000, dueAtMs = now - hour)
        val dueToday = todo(id = 2, createdAtMs = 9_000, dueAtMs = now + 2 * hour)
        val undatedEarly = todo(id = 3, createdAtMs = 1_000, dueAtMs = null)
        val future = todo(id = 4, createdAtMs = 2_000, dueAtMs = now + 2 * day)
        val undatedLate = todo(id = 5, createdAtMs = 3_000, dueAtMs = null)

        val ordered = orderCareTodosForHome(
            listOf(undatedLate, future, dueToday, undatedEarly, overdue),
            now,
            zone,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ordered.map { it.id })
    }

    @Test
    fun `caps visible rows at three and reports the remainder`() {
        val todos = (1..5).map { todo(id = it.toLong(), createdAtMs = it * 1_000L) }

        val block = buildHomeTodoBlock(todos, now, zone)!!

        assertEquals(listOf(1L, 2L, 3L), block.visible.map { it.todo.id })
        assertEquals(5, block.totalCount)
        assertTrue(block.hasMore)
        assertEquals(2, block.hiddenCount)
    }

    @Test
    fun `exactly three open todos do not show a view-all affordance`() {
        val todos = (1..3).map { todo(id = it.toLong(), createdAtMs = it * 1_000L) }

        val block = buildHomeTodoBlock(todos, now, zone)!!

        assertEquals(3, block.visible.size)
        assertFalse(block.hasMore)
        assertEquals(0, block.hiddenCount)
    }

    @Test
    fun `no open todos yields no block at all`() {
        assertNull(buildHomeTodoBlock(emptyList(), now, zone))

        val done = todo(id = 1, createdAtMs = 1_000, status = CareTodoStatus.DONE)
        val cancelled = todo(id = 2, createdAtMs = 2_000, status = CareTodoStatus.CANCELLED)
        assertNull("终态不得进入首页区块", buildHomeTodoBlock(listOf(done, cancelled), now, zone))
    }

    @Test
    fun `a todo without a due date never becomes overdue`() {
        val ancientUndated = todo(id = 1, createdAtMs = 1L, dueAtMs = null)
        assertEquals(HomeTodoDueBucket.UNDATED, homeTodoDueBucket(ancientUndated, now, zone))
        assertEquals(HomeTodoDueBucket.UNDATED, homeTodoDueBucket(todo(id = 2, createdAtMs = 1L), now, zone))
    }

    @Test
    fun `overdue and due-today buckets are distinguished`() {
        assertEquals(
            HomeTodoDueBucket.OVERDUE,
            homeTodoDueBucket(todo(id = 1, createdAtMs = 1, dueAtMs = now - 1), now, zone),
        )
        assertEquals(
            HomeTodoDueBucket.DUE_TODAY,
            homeTodoDueBucket(todo(id = 2, createdAtMs = 1, dueAtMs = now + 1), now, zone),
        )
        assertEquals(
            HomeTodoDueBucket.UNDATED,
            homeTodoDueBucket(todo(id = 3, createdAtMs = 1, dueAtMs = now + day), now, zone),
        )
    }

    private fun todo(id: Long, createdAtMs: Long, dueAtMs: Long? = null, status: String = CareTodoStatus.OPEN) =
        CareTodo(
            id = id,
            careRecipientId = 1L,
            title = "待办$id",
            status = status,
            dueAtMs = dueAtMs,
            createdAtMs = createdAtMs,
        )
}
