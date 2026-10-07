package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
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
 * 照护笔记仓库成员收口、状态机、归属可见数据、挂接/悬挂与正文不可改写（docs/care-notes.md §2–§6）。
 *
 * 这些是不依赖 Android 运行时的纯 JVM 断言，真正随 `:app:testDebugUnitTest` 执行。
 */
class CareNoteRepositoryImplTest {

    private val dao = FakeCareNoteDao()
    private val clock = MutableClock(Instant.ofEpochMilli(NOW))

    private fun repoFor(recipientId: Long): CareNoteRepositoryImpl {
        val store: ActiveRecipientStore = mock {
            on { this.recipientId } doReturn MutableStateFlow(recipientId)
            onBlocking { current() } doReturn recipientId
        }
        return CareNoteRepositoryImpl(dao, store, clock)
    }

    @Test
    fun `reads are scoped to the active recipient and empty without one`() = runBlocking {
        dao.seed(note(recipientId = 1, title = "爸爸的笔记"))
        dao.seed(note(recipientId = 2, title = "妈妈的笔记"))

        assertEquals(listOf("爸爸的笔记"), repoFor(1).observeNotes().first().map { it.note.title })
        assertEquals(listOf("妈妈的笔记"), repoFor(2).observeNotes().first().map { it.note.title })

        // NO_RECIPIENT：读**不发射**（等价于空），不抛错——与 CareTodoRepositoryImpl 同一约定
        assertNull(withTimeoutOrNull(200L) { repoFor(ActiveRecipientStore.NO_RECIPIENT).observeNotes().first() })
        assertNull(
            withTimeoutOrNull(200L) {
                repoFor(ActiveRecipientStore.NO_RECIPIENT).observeRelatedNotes("MEDICATION", 1).first()
            },
        )
    }

    @Test
    fun `writes throw without an active recipient`(): Unit = runBlocking {
        val none = repoFor(ActiveRecipientStore.NO_RECIPIENT)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                none.createNote(
                    title = "笔记",
                    body = "正文",
                    attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.setStatus(1L, CareNoteStatus.ACTIVE) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.addLink(1L, CareNoteTargetType.MEDICATION, 1L) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { none.deleteNote(1L) }
        }
    }

    @Test
    fun `create stamps the active recipient starts ACTIVE and never rewrites the body`(): Unit = runBlocking {
        val rawBody = "  观察：饭后半小时服药\n第二行原文  "
        val id = repoFor(7L).createNote(
            title = "  护士交代  ",
            body = rawBody,
            attributionType = CareNoteAttributionType.CLINICIAN,
            attributionName = "  王医生  ",
        )
        val stored = dao.storedById(id)!!
        assertEquals(7L, stored.careRecipientId)
        assertEquals("护士交代", stored.title)
        assertEquals(rawBody, stored.body) // 正文逐字符原样，绝不改写
        assertEquals(CareNoteStatus.ACTIVE, stored.status)
        assertEquals(CareNoteAttributionType.CLINICIAN, stored.attributionType)
        assertEquals("王医生", stored.attributionName)
        assertEquals(NOW, stored.createdAtMs)
        assertNull(stored.updatedAtMs)
        assertNull(stored.supersededText)
    }

    @Test
    fun `attribution type defaults to the conservative personal observation and is always a concrete value`(): Unit =
        runBlocking {
            val id = repoFor(
                1L,
            ).createNote(title = "随意记", body = "今天有点咳嗽", attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION)
            val stored = dao.storedById(id)!!
            // 名称为空但类型恒在——任何展示面都不得出现无归属的裸正文（§2）。
            assertNull(stored.attributionName)
            assertEquals(CareNoteAttributionType.PERSONAL_OBSERVATION, stored.attributionType)
        }

    @Test
    fun `setStatus only through the user path and supersede fields are set then cleared`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createNote(title = "护士交代", body = "饭后服药", attributionType = CareNoteAttributionType.CLINICIAN)

        clock.now = Instant.ofEpochMilli(NOW + 1_000)
        repo.setStatus(id, CareNoteStatus.QUESTIONABLE)
        assertEquals(CareNoteStatus.QUESTIONABLE, dao.storedById(id)!!.status)
        assertNull("QUESTIONABLE 不携带被更新说明", dao.storedById(id)!!.supersededText)

        clock.now = Instant.ofEpochMilli(NOW + 2_000)
        repo.setStatus(id, CareNoteStatus.SUPERSEDED, supersededText = "改到饭后即服")
        val superseded = dao.storedById(id)!!
        assertEquals(CareNoteStatus.SUPERSEDED, superseded.status)
        assertEquals("改到饭后即服", superseded.supersededText)
        assertEquals(NOW + 2_000, superseded.supersededAtMs)

        // 切走 SUPERSEDED：清空 superseded 字段，避免残留两套关闭语义
        repo.setStatus(id, CareNoteStatus.ACTIVE)
        val active = dao.storedById(id)!!
        assertEquals(CareNoteStatus.ACTIVE, active.status)
        assertNull(active.supersededText)
        assertNull(active.supersededAtMs)

        // 只有用户能设置：非法状态被拒绝
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.setStatus(id, "ARCHIVED") }
        }
    }

    @Test
    fun `list default hides superseded while search still returns it`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val activeId = repo.createNote(
            title = "常见问题",
            body = "每天两次",
            attributionType = CareNoteAttributionType.CLINICIAN,
        )
        val supersededId = repo.createNote(
            title = "旧说法",
            body = "曾经每天三次",
            attributionType = CareNoteAttributionType.CLINICIAN,
        )
        repo.setStatus(supersededId, CareNoteStatus.SUPERSEDED, supersededText = "现改为每天两次")

        // 默认列表：SUPERSEDED 折叠/隐藏
        assertEquals(listOf(activeId), repo.observeNotes().first().map { it.note.id })

        // 主动搜索：仍返回命中的 SUPERSEDED（避免造成记录丢失的错觉，§6）
        val found = repo.observeNotes("每天").first()
        assertEquals(setOf(activeId, supersededId), found.map { it.note.id }.toSet())
        assertTrue(found.any { it.note.status == CareNoteStatus.SUPERSEDED })
    }

    @Test
    fun `questionable sorts above active`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val activeId = repo.createNote(
            title = "A",
            body = "a",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
        )
        val questionableId = repo.createNote(
            title = "B",
            body = "b",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
        )
        repo.setStatus(questionableId, CareNoteStatus.QUESTIONABLE)

        assertEquals(listOf(questionableId, activeId), repo.observeNotes().first().map { it.note.id })
    }

    @Test
    fun `add and remove links`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createNote(
            title = "笔记",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
        )

        repo.addLink(id, CareNoteTargetType.MEDICATION, 42L)
        repo.addLink(id, CareNoteTargetType.MEDICATION, 42L) // 幂等
        repo.addLink(id, CareNoteTargetType.CARE_TASK, 7L)
        val links = repo.linksForNote(id)
        assertEquals(2, links.size)

        repo.removeLink(links.first { it.targetId == 7L }.id)
        assertEquals(listOf(42L), repo.linksForNote(id).map { it.targetId })
    }

    @Test
    fun `related notes are scoped by target and carry member isolation`(): Unit = runBlocking {
        dao.seedTarget(CareNoteTargetType.MEDICATION, 5L)
        val dadId = repoFor(1L).createNote(
            title = "爸爸的药笔记",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.MEDICATION, 5L)),
        )
        dao.seed(note(recipientId = 2, title = "妈妈的无关笔记"))

        assertEquals(listOf(dadId), repoFor(1L).observeRelatedNotes("MEDICATION", 5L).first().map { it.id })
        assertEquals(emptyList<Long>(), repoFor(1L).observeRelatedNotes("MEDICATION", 999L).first().map { it.id })
    }

    @Test
    fun `dangling target is tolerated on read and removable on demand`(): Unit = runBlocking {
        val repo = repoFor(1L)
        // 目标从未存在 → 悬挂
        val id = repo.createNote(
            title = "笔记",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.MEDICATION, 99L)),
        )

        // 读取容忍：笔记照常返回、不抛错，并标出悬挂 link
        val rows = repo.observeNotes().first()
        assertEquals(1, rows.size)
        assertEquals(id, rows.first().note.id)
        assertEquals(listOf(99L), rows.first().danglingLinks.map { it.targetId })
        assertTrue(repo.isTargetMissing(CareNoteTargetType.MEDICATION, 99L))

        // 一键清除悬挂关联（仅在显式调用时发生，无后台清理）
        repo.removeDanglingLinks(id)
        assertTrue(repo.linksForNote(id).isEmpty())
        assertEquals(1, repo.observeNotes().first().size) // 笔记本身不删除
    }

    @Test
    fun `update reconciles links keeps status and preserves the body verbatim`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val id = repo.createNote(
            title = "旧标题",
            body = "旧正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.MEDICATION, 1L)),
        )
        repo.setStatus(id, CareNoteStatus.QUESTIONABLE)

        clock.now = Instant.ofEpochMilli(NOW + 5_000)
        val newBody = "  新正文  \n保留缩进 "
        repo.updateNote(
            id = id,
            title = "新标题",
            body = newBody,
            attributionType = CareNoteAttributionType.CAREGIVER_EXPERIENCE,
            links = listOf(CareNoteTarget(CareNoteTargetType.CARE_TASK, 3L)),
        )

        val stored = dao.storedById(id)!!
        assertEquals("新标题", stored.title)
        assertEquals(newBody, stored.body) // 正文原样
        assertEquals(CareNoteStatus.QUESTIONABLE, stored.status) // 状态不被 update 改动
        assertEquals(CareNoteAttributionType.CAREGIVER_EXPERIENCE, stored.attributionType)
        assertEquals(NOW + 5_000, stored.updatedAtMs)
        assertEquals(
            listOf(3L to CareNoteTargetType.CARE_TASK),
            repo.linksForNote(id).map {
                it.targetId to
                    it.targetType
            },
        )
    }

    @Test
    fun `deleteNote cascades its links without touching sibling notes or their links`(): Unit = runBlocking {
        val repo = repoFor(1L)
        val doomed = repo.createNote(
            title = "要删除的",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(
                CareNoteTarget(CareNoteTargetType.MEDICATION, 1L),
                CareNoteTarget(CareNoteTargetType.CARE_TASK, 2L),
            ),
        )
        val survivor = repo.createNote(
            title = "保留的",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.TODO, 9L)),
        )
        assertEquals(3, dao.storedLinks().size)

        repo.deleteNote(doomed)

        assertNull(dao.storedById(doomed))
        assertTrue("删除笔记必须级联删除其 care_note_links", dao.storedLinks().none { it.noteId == doomed })
        // 只动目标笔记：兄弟笔记与其 links 逐行保留（不波及其他记录）。
        assertEquals(listOf(survivor), dao.stored().map { it.id })
        assertEquals(listOf(9L), repo.linksForNote(survivor).map { it.targetId })
        assertEquals(1, dao.storedLinks().size)
    }

    private fun note(recipientId: Long, title: String) = CareNote(
        careRecipientId = recipientId,
        title = title,
        body = "正文",
        attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
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
