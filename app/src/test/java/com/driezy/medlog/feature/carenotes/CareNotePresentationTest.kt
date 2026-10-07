package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就地「相关笔记」呈现的纯逻辑守护（docs/care-notes.md §1/§2/§7）：
 * 空态不渲染、归属类型恒可见、SUPERSEDED 折叠但可检索、正文绝不改写。
 */
class CareNotePresentationTest {

    @Test
    fun `empty related notes render nothing`() {
        assertFalse(CareNotePresentation.shouldRenderRelated(emptyList()))
        assertTrue(CareNotePresentation.shouldRenderRelated(listOf(note(id = 1))))
    }

    @Test
    fun `attribution type is present even when the attribution name is empty`() {
        val bare = note(id = 1, attributionName = null)
        val fields = CareNotePresentation.attributionFields(bare)

        // 第一位恒为类型：任何展示面都不得出现无归属的裸正文。
        assertEquals(CareNoteAttributionField.TYPE, fields.first())
        assertTrue(fields.contains(CareNoteAttributionField.TYPE))
        assertFalse("名称为空时不应出现 NAME 段", fields.contains(CareNoteAttributionField.NAME))

        val named = note(id = 2, attributionName = "王医生")
        assertTrue(CareNotePresentation.attributionFields(named).contains(CareNoteAttributionField.NAME))
    }

    @Test
    fun `superseded rows are folded with a marker but remain retrievable`() {
        val superseded = note(id = 1, status = CareNoteStatus.SUPERSEDED, supersededText = "改到饭后即服")

        assertTrue(CareNotePresentation.isFolded(superseded))
        assertEquals(CareNoteStatusMarker.SUPERSEDED, CareNotePresentation.statusMarker(superseded))
        // 折叠但可检索：被更新说明仍在 note 上，展开时可读取。
        assertEquals("改到饭后即服", superseded.supersededText)

        assertEquals(
            CareNoteStatusMarker.QUESTIONABLE,
            CareNotePresentation.statusMarker(note(id = 2, status = CareNoteStatus.QUESTIONABLE)),
        )
        assertEquals(
            CareNoteStatusMarker.NONE,
            CareNotePresentation.statusMarker(note(id = 3, status = CareNoteStatus.ACTIVE)),
        )
    }

    @Test
    fun `nobody ever rewrites the body text`() {
        // 正文含疑似因果表述：App 仍必须逐字符原样呈现，绝不改写。
        val raw = "吃了这味药以后就不咳嗽了"
        val observation = note(id = 1, body = raw, attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION)
        assertEquals(raw, CareNotePresentation.body(observation))
    }

    @Test
    fun `personal observations use neutral framing and are not conclusions`() {
        assertTrue(
            CareNotePresentation.isObservation(
                note(id = 1, attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION),
            ),
        )
        assertFalse(
            CareNotePresentation.isObservation(note(id = 2, attributionType = CareNoteAttributionType.CLINICIAN)),
        )
    }

    private fun note(
        id: Long,
        body: String = "正文",
        attributionType: String = CareNoteAttributionType.PERSONAL_OBSERVATION,
        attributionName: String? = null,
        status: String = CareNoteStatus.ACTIVE,
        supersededText: String? = null,
    ) = CareNote(
        id = id,
        careRecipientId = 1L,
        title = "标题$id",
        body = body,
        attributionType = attributionType,
        attributionName = attributionName,
        status = status,
        supersededText = supersededText,
    )
}
