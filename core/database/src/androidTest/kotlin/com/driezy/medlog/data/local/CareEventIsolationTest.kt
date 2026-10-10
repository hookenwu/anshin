package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.model.CareEventLog
import com.driezy.medlog.data.model.CareRecipient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 照护事件数据层验收：成员维度隔离、级联删除、最新锚点查询（docs/tracked-events-spec.md §4/§8）。
 */
@RunWith(AndroidJUnit4::class)
class CareEventIsolationTest {

    private fun inMemoryDatabase(): MedLogDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MedLogDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun eventsAreIsolatedPerRecipient() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadEvent = database.careEventLogDao().insert(event(dad, 1_700_000_000_000L))
            val momEvent = database.careEventLogDao().insert(event(mom, 1_700_000_500_000L))

            assertEquals(listOf(dadEvent), database.careEventLogDao().getLogsOnce(dad, CareEventKind.BOWEL).map { it.id })
            assertEquals(listOf(momEvent), database.careEventLogDao().getLogsOnce(mom, CareEventKind.BOWEL).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun newestAnchorIsTheLatestByOccurredAtNotByInsertOrder() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            // 先插入「较早发生」的一条，再补记一条更早的：锚点必须是 occurredAtMs 最大者。
            val newer = database.careEventLogDao().insert(event(dad, 1_700_000_000_000L))
            database.careEventLogDao().insert(event(dad, 1_600_000_000_000L))
            val newest = database.careEventLogDao().getNewestOnce(dad, CareEventKind.BOWEL)
            assertNotNull(newest)
            assertEquals(newer, newest!!.id)
            assertEquals(1_700_000_000_000L, newest.occurredAtMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingRecipientCascadesItsEvents() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dad = newRecipient(database, "爸爸")
            val mom = newRecipient(database, "妈妈")
            val dadEvent = database.careEventLogDao().insert(event(dad, 1_700_000_000_000L))
            val momEvent = database.careEventLogDao().insert(event(mom, 1_700_000_000_000L))

            database.careRecipientDao().deleteById(dad)

            assertNull(database.careRecipientDao().getById(dad))
            assertNull(database.careEventLogDao().getById(dadEvent))
            assertNotNull("删除爸爸不得影响妈妈的事件", database.careEventLogDao().getById(momEvent))
        } finally {
            database.close()
        }
    }

    private suspend fun newRecipient(database: MedLogDatabase, name: String): Long =
        database.careRecipientDao().insert(CareRecipient(uuid = CareRecipient.newUuid(), displayName = name))

    private fun event(recipientId: Long, occurredAtMs: Long) = CareEventLog(
        careRecipientId = recipientId,
        kind = CareEventKind.BOWEL,
        occurredAtMs = occurredAtMs,
        createdAtMs = occurredAtMs,
    )
}
