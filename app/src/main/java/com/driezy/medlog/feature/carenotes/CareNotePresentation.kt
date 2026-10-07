package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus

/** 归属行的字段顺序（docs/care-notes.md §2）。 */
enum class CareNoteAttributionField {
    /** 归属类型——**恒在**，任何展示面都不得出现无归属的裸正文。 */
    TYPE,
    NAME,
    TIME,
    SOURCE,
}

/** 状态标记：ACTIVE 不加标签/极轻标注，故为 [NONE]。 */
enum class CareNoteStatusMarker { NONE, QUESTIONABLE, SUPERSEDED }

/**
 * 照护笔记呈现的**纯逻辑**（无 Android 依赖，可 JVM 单测）。
 *
 * 硬规则（docs/care-notes.md §1/§2）：App **不改写正文**（[body] 原样返回）、
 * **归属类型恒可见**（[attributionFields] 永远以 [CareNoteAttributionField.TYPE] 开头）、
 * 不把个人观察渲染成因果结论（[isObservation] 只用于中性「我观察到…」框架）。
 */
object CareNotePresentation {

    /** 正文原样返回——任何代码路径都不得改写用户正文。 */
    fun body(note: CareNote): String = note.body

    /**
     * 归属行要展示的字段。**第一位恒为 [CareNoteAttributionField.TYPE]**，名称/时间/出处
     * 有则追加、无则省略——因此即使名称为空，类型也始终可见。
     */
    fun attributionFields(note: CareNote): List<CareNoteAttributionField> = buildList {
        add(CareNoteAttributionField.TYPE)
        if (!note.attributionName.isNullOrBlank()) add(CareNoteAttributionField.NAME)
        if (note.attributionAtMs != null) add(CareNoteAttributionField.TIME)
        if (!note.attributionText.isNullOrBlank()) add(CareNoteAttributionField.SOURCE)
    }

    /** 是否为个人观察：以中性「我观察到…」框架呈现，绝不写成因果句式。 */
    fun isObservation(note: CareNote): Boolean = note.attributionType == CareNoteAttributionType.PERSONAL_OBSERVATION

    fun statusMarker(note: CareNote): CareNoteStatusMarker = when (note.status) {
        CareNoteStatus.QUESTIONABLE -> CareNoteStatusMarker.QUESTIONABLE
        CareNoteStatus.SUPERSEDED -> CareNoteStatusMarker.SUPERSEDED
        else -> CareNoteStatusMarker.NONE
    }

    /** SUPERSEDED 默认折叠到次级位置（可展开看 supersededText）。 */
    fun isFolded(note: CareNote): Boolean = note.status == CareNoteStatus.SUPERSEDED

    /** 就地「相关笔记」卡片：空态**不渲染**（无 header、无占位）。 */
    fun shouldRenderRelated(notes: List<CareNote>): Boolean = notes.isNotEmpty()
}
