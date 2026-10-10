package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.model.CareEventLog
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
 * 照护事件仓库：成员收口、双时间戳、就地编辑/物理删除（docs/tracked-events-spec.md §4 D2/D4、§8）。
 *
 * 纯 JVM 断言，随 `:app:testDebugUnitTest` 执行。
 */
class CareEventRepositoryImplTest {

    private val dao = FakeCareEventLogDao()
    private val clock = MutableClock(Instant.ofEpochMilli(NOW))

    private fun repoFor(recipientId: Long): CareEventRepositoryImpl {
        val store: ActiveRecipientStore = mock {
            on { this.recipientId } doReturn MutableStateFlow(recipientId)
            onBlocking { current() } doReturn recipientId
        }
        return CareEventRepositoryImpl(dao, store, clock)
    }

    @Test
    fun `reads are scoped to the active recipient and empty without one`() = runBlocking {
        dao.seed(event(recipientId = 1, occurredAtMs = NOW - 1_000))
        dao.seed(event(recipientId = 2, occurredAtMs = NOW - 2_000))

        assertEquals(1, repoFor(1).getLogs().first().size)
        assertEquals(1, repoFor(2).getLogs().first().size)
        assertEquals(1, repoFor(1).getLogsOnce().size)
        assertEquals(NOW - 1_000, repoFor(1).getNewest().first()!!.occurredAtMs)

        // NO_RECIPIENT：读**不发射**（flow 等价空）、一次性读返回空/null，不抛错
        assertNull(withTimeoutOrNull(200L) { repoFor(ActiveRecipientStore.NO_RECIPIENT).getLogs().first() })
        assertEquals(emptyList<CareEventLog>(), repoFor(ActiveRecipientStore.NO_RECIPIENT).getLogsOnce())
        assertNull(repoFor(ActiveRecipientStore.NO_RECIPIENT).getNewestOnce())
    }

    @Test
    fun `writes throw without an active recipient`(): Unit = runBlocking {
        val none = repoFor(ActiveRecipientStore.NO_RECIPIENT)
        assertThrows(IllegalStateException::class.java) { runBlocking { none.record() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { none.edit(1L, NOW) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { none.delete(1L) } }
    }

    @Test
    fun `record stamps the active recipient and defaults occurredAt to now`(): Unit = runBlocking {
        val id = repoFor(7L).record(note = "  室内  ")
        val stored = dao.storedById(id)!!
        assertEquals(7L, stored.careRecipientId)
        assertEquals(CareEventKind.BOWEL, stored.kind)
        assertEquals(NOW, stored.occurredAtMs)
        assertEquals(NOW, stored.createdAtMs)
        assertEquals("室内", stored.note)
        assertNull(stored.updatedAtMs)
    }

    @Test
    fun `back-dated record keeps occurredAt in the past while createdAt is the entry time`(): Unit = runBlocking {
        val past = NOW - 3 * 86_400_000L
        val id = repoFor(1L).record(occurredAtMs = past)
        val stored = dao.storedById(id)!!
        assertEquals("补记的发生时刻必须保留", past, stored.occurredAtMs)
        assertEquals("录入时刻是现在", NOW, stored.createdAtMs)
    }

    @Test
    fun `edit rewrites occurredAt in place stamps updatedAt and keeps identity and createdAt`(): Unit = runBlocking {
        val id = repoFor(1L).record(occurredAtMs = NOW - 1_000)
        clock.now = Instant.ofEpochMilli(NOW + 5_000)
        repoFor(1L).edit(id, occurredAtMs = NOW - 9_000, note = "改正")

        val stored = dao.storedById(id)!!
        assertEquals(NOW - 9_000, stored.occurredAtMs)
        assertEquals("改正", stored.note)
        assertEquals(NOW + 5_000, stored.updatedAtMs)
        assertEquals(id, stored.id)
        assertEquals(1L, stored.careRecipientId)
        assertEquals("就地编辑不改录入时刻", NOW, stored.createdAtMs)
    }

    @Test
    fun `delete removes the row physically`(): Unit = runBlocking {
        val id = repoFor(1L).record()
        assertEquals(1, dao.stored().size)
        repoFor(1L).delete(id)
        assertNull(dao.storedById(id))
        assertEquals(emptyList<CareEventLog>(), repoFor(1L).getLogsOnce())
    }

    private fun event(recipientId: Long, occurredAtMs: Long) = CareEventLog(
        careRecipientId = recipientId,
        kind = CareEventKind.BOWEL,
        occurredAtMs = occurredAtMs,
        createdAtMs = occurredAtMs,
    )

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
