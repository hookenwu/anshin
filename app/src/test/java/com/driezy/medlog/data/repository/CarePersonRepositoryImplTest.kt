package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CarePerson
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 人员档案仓库：成员收口、姓名逐字存储、CRUD 与删除引用计数（docs/care-people.md §1/§5）。
 * 纯 JVM 断言，随 `:app:testDebugUnitTest` 执行。
 */
class CarePersonRepositoryImplTest {

    private val dao = FakeCarePersonDao()
    private val clock = MutableClock(Instant.ofEpochMilli(NOW))

    private fun repoFor(recipientId: Long): CarePersonRepositoryImpl {
        val store: ActiveRecipientStore = mock {
            on { this.recipientId } doReturn MutableStateFlow(recipientId)
            onBlocking { current() } doReturn recipientId
        }
        return CarePersonRepositoryImpl(dao, store, clock)
    }

    @Test
    fun `reads are scoped to the active recipient and empty without one`() = runBlocking {
        dao.seed(CarePerson(careRecipientId = 1, name = "护士张"))
        dao.seed(CarePerson(careRecipientId = 2, name = "妈妈的护工"))

        assertEquals(listOf("护士张"), repoFor(1).observePeople().first().map { it.name })
        assertEquals(listOf("妈妈的护工"), repoFor(2).observePeople().first().map { it.name })

        // NO_RECIPIENT：读**不发射**（等价空），不抛错——与既有仓库同一约定
        assertNull(withTimeoutOrNull(200L) { repoFor(ActiveRecipientStore.NO_RECIPIENT).observePeople().first() })
        assertNull(
            withTimeoutOrNull(200L) {
                repoFor(ActiveRecipientStore.NO_RECIPIENT).searchPeople("护士").first()
            },
        )
    }

    @Test
    fun `search matches by name substring within the active recipient only`() = runBlocking {
        dao.seed(CarePerson(careRecipientId = 1, name = "护士张"))
        dao.seed(CarePerson(careRecipientId = 1, name = "王医生"))
        dao.seed(CarePerson(careRecipientId = 2, name = "护士李"))

        assertEquals(listOf("护士张"), repoFor(1).searchPeople("护士").first().map { it.name })
        assertEquals(listOf("王医生"), repoFor(1).searchPeople("医").first().map { it.name })
        assertTrue(repoFor(1).searchPeople("李").first().isEmpty())
    }

    @Test
    fun `writes throw without an active recipient`(): Unit = runBlocking {
        val none = repoFor(ActiveRecipientStore.NO_RECIPIENT)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.createPerson("护士张") }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.updatePerson(1L, "护士张") }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.deletePerson(1L) }
        }
    }

    @Test
    fun `create stamps the active recipient and stores the name verbatim`(): Unit = runBlocking {
        val id = repoFor(7L).createPerson(
            name = "  护士张 ",
            gender = "女",
            approxAge = 30,
            hospital = "市一院",
            agency = "",
            phone = "13800000000",
        )
        val stored = dao.storedById(id)!!
        assertEquals(7L, stored.careRecipientId)
        // 姓名原样存储，不做规范化（用户输入什么就是什么）。
        assertEquals("  护士张 ", stored.name)
        assertEquals("女", stored.gender)
        assertEquals(30, stored.approxAge)
        assertEquals("市一院", stored.hospital)
        assertNull("空白可选字段视为未填写", stored.agency)
        assertEquals("13800000000", stored.phone)
        assertEquals(NOW, stored.createdAtMs)
        assertNull(stored.updatedAtMs)
    }

    @Test
    fun `create requires a non blank name`(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repoFor(1L).createPerson("   ") }
        }
    }

    @Test
    fun `update refreshes updatedAt keeps identity and refuses a cross member person`(): Unit = runBlocking {
        val id = dao.seed(CarePerson(careRecipientId = 1, name = "护士张", createdAtMs = NOW))

        clock.now = Instant.ofEpochMilli(NOW + 1_000)
        repoFor(
            1L,
        ).updatePerson(id, name = "张护士", gender = "女", approxAge = null, hospital = null, agency = null, phone = null)
        val stored = dao.storedById(id)!!
        assertEquals("张护士", stored.name)
        assertEquals(1L, stored.careRecipientId)
        assertEquals(NOW, stored.createdAtMs)
        assertEquals(NOW + 1_000, stored.updatedAtMs)

        // 跨成员：当前成员 2 不得改 1 号成员的人员（成员隔离）。
        clock.now = Instant.ofEpochMilli(NOW + 2_000)
        repoFor(2L).updatePerson(id, name = "被篡改")
        assertEquals("张护士", dao.storedById(id)!!.name)
    }

    @Test
    fun `delete removes the row and the note count is exposed for the prompt`(): Unit = runBlocking {
        val id = dao.seed(CarePerson(careRecipientId = 1, name = "护士张"))
        dao.setNoteCount(id, 3)
        assertEquals(3, repoFor(1L).countNotesReferencing(id))

        repoFor(1L).deletePerson(id)
        assertNull(dao.storedById(id))
    }

    @Test
    fun `personBelongsToCurrentRecipient respects membership and NO_RECIPIENT`(): Unit = runBlocking {
        val id = dao.seed(CarePerson(careRecipientId = 1, name = "护士张"))
        assertTrue(repoFor(1L).personBelongsToCurrentRecipient(id))
        assertTrue(!repoFor(2L).personBelongsToCurrentRecipient(id))
        assertTrue(!repoFor(ActiveRecipientStore.NO_RECIPIENT).personBelongsToCurrentRecipient(id))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
