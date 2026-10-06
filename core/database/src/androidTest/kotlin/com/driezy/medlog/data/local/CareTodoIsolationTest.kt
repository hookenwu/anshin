package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 待办数据层验收：成员维度隔离、级联删除、开放/历史分流（docs/todos.md §2.5）。
 */
@RunWith(AndroidJUnit4::class)
class CareTodoIsolationTest {

    private fun inMemoryDatabase(): MedLogDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MedLogDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun todosAreIsolatedPerRecipient() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadTodo = database.careTodoDao().insert(todo(dad, "让护士看压疮风险"))
            val momTodo = database.careTodoDao().insert(todo(mom, "复查预约"))

            assertEquals(listOf(dadTodo), database.careTodoDao().getOpenTodosOnce(dad).map { it.id })
            assertEquals(listOf(momTodo), database.careTodoDao().getOpenTodosOnce(mom).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun openAndHistoryAreSplitByStatus() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val openId = database.careTodoDao().insert(todo(dad, "未闭环"))
            val doneId = database.careTodoDao().insert(
                todo(dad, "已完成").copy(status = CareTodoStatus.DONE, closedAtMs = 10L),
            )
            val cancelledId = database.careTodoDao().insert(
                todo(dad, "已取消").copy(status = CareTodoStatus.CANCELLED, closedAtMs = 20L),
            )

            assertEquals(listOf(openId), database.careTodoDao().getOpenTodosOnce(dad).map { it.id })

            val history = database.careTodoDao().getHistoryTodos(dad).first()
            // 历史按 closedAtMs 降序：取消(20) 在 完成(10) 之前
            assertEquals(listOf(cancelledId, doneId), history.map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingRecipientCascadesItsTodos() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadTodo = database.careTodoDao().insert(todo(dad, "爸爸的待办"))
            val momTodo = database.careTodoDao().insert(todo(mom, "妈妈的待办"))

            database.careRecipientDao().deleteById(dad)

            assertNull(database.careRecipientDao().getById(dad))
            assertNull(database.careTodoDao().getById(dadTodo))
            assertNotNull("删除爸爸不得影响妈妈的待办", database.careTodoDao().getById(momTodo))
            assertTrue(database.careTodoDao().getOpenTodosOnce(mom).isNotEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun noStoredOverdueState_isDerivedNotPersisted() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            // 无 dueAtMs 的待办永久可读；逾期不是列，不存在落库的 OVERDUE 行。
            val noDue = database.careTodoDao().insert(todo(dad, "无截止"))
            val stored = database.careTodoDao().getById(noDue)!!
            assertEquals(null, stored.dueAtMs)
            assertTrue(CareTodoStatus.all.none { it == "OVERDUE" })
        } finally {
            database.close()
        }
    }

    private suspend fun newRecipient(database: MedLogDatabase, name: String): Long =
        database.careRecipientDao().insert(CareRecipient(uuid = CareRecipient.newUuid(), displayName = name))

    private fun todo(recipientId: Long, title: String) = CareTodo(
        careRecipientId = recipientId,
        title = title,
    )
}
