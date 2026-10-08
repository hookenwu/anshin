package com.driezy.medlog.feature.records

import com.driezy.medlog.data.model.SymptomLog
import com.driezy.medlog.data.repository.CareNoteWithState

/** 记录中心的两类记录（只读浏览用；不产生第三张表，不跨表写入）。 */
sealed interface RecordEntry {
    val key: String

    /** 照护笔记条目（携带悬挂关联，卡片渲染复用 [com.driezy.medlog.feature.carenotes.CareNoteCard]）。 */
    data class CareNoteEntry(val row: CareNoteWithState) : RecordEntry {
        override val key: String get() = "note:${row.note.id}"
    }

    /** 身心记录条目。 */
    data class DiaryEntry(val log: SymptomLog) : RecordEntry {
        override val key: String get() = "diary:${log.id}"
    }
}

/**
 * 记录中心纯逻辑（无 Android 依赖，JVM 可测）。
 *
 * 排序硬规则（docs/record-center-spec.md §3 D6）：
 * - 「全部」混排一律按**事件时间**倒序：身心记录用 `recordedAt`，照护笔记用 `createdAtMs`；
 *   **`updatedAtMs` 绝不参与混排排序**——否则编辑一条旧笔记会把它顶到时间轴最前，
 *   被误当成「今天的记录」。编辑只更新内容，不改变它在时间轴里的位置。
 * - 同值时以类型固定顺序（照护笔记优先）+ `id` 倒序，保证稳定可复现。
 * - **与「照护笔记」单类型模式的差异（切勿"统一"掉）**：单类型模式沿用既有排序
 *   （`QUESTIONABLE` > `ACTIVE` > `SUPERSEDED`，同级按 `updatedAtMs` 倒序）——类型内部
 *   「待确认优先」有意义；混排视图**不采用**该规则。两套排序刻意不同，不是遗漏。
 */
object RecordCenterPresentation {

    /** 照护笔记在混排同值时的类型序（照护笔记优先）。 */
    private const val TYPE_RANK_CARE_NOTE = 0
    private const val TYPE_RANK_DIARY = 1

    /**
     * 产出当前模式下的条目列表。
     *
     * 入参 [notes] 由仓库按当前成员与关键词产出（空关键词 → 默认列表，`SUPERSEDED` 折叠；
     * 关键词非空 → 主动搜索，命中的 `SUPERSEDED` 也会返回，由卡片标「已被更新」）。
     * 入参 [logs] 为当前成员的全部身心记录。
     */
    fun entries(
        mode: RecordsMode,
        query: String,
        notes: List<CareNoteWithState>,
        logs: List<SymptomLog>,
    ): List<RecordEntry> = when (mode) {
        RecordsMode.ALL -> orderMixed(notes, logs.filter { diaryMatches(it, query) })
        // 身心记录模式不提供搜索（现无此能力，本次不引入任何筛选）。
        RecordsMode.DIARY -> logs.map { RecordEntry.DiaryEntry(it) }
        RecordsMode.CARE_NOTES -> notes.map { RecordEntry.CareNoteEntry(it) }
    }

    /**
     * 「全部」混排：事件时间倒序；同值以类型（照护笔记优先）再以 id 倒序。
     * 排序输入保持稳定（Kotlin `sortedWith` 稳定排序），故仓库层的稳定序在相等时被保留。
     */
    fun orderMixed(notes: List<CareNoteWithState>, logs: List<SymptomLog>): List<RecordEntry> =
        (notes.map { RecordEntry.CareNoteEntry(it) } + logs.map { RecordEntry.DiaryEntry(it) })
            .sortedWith(
                compareByDescending<RecordEntry> { eventTime(it) }
                    .thenBy { typeRank(it) }
                    .thenByDescending { idOf(it) },
            )

    /** 事件时间：身心记录用 `recordedAt`，照护笔记用 `createdAtMs`。`updatedAtMs` 不参与。 */
    fun eventTime(entry: RecordEntry): Long = when (entry) {
        is RecordEntry.CareNoteEntry -> entry.row.note.createdAtMs
        is RecordEntry.DiaryEntry -> entry.log.recordedAt
    }

    /**
     * 跨类型搜索：身心记录匹配备注与症状/副作用文本（不区分大小写）。
     * 关键词为空视为命中全部（普通列表）。照护笔记的匹配由仓库负责（title/body/attributionName）。
     */
    fun diaryMatches(log: SymptomLog, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return log.note.contains(trimmed, ignoreCase = true) ||
            log.symptoms.contains(trimmed, ignoreCase = true) ||
            log.sideEffects.contains(trimmed, ignoreCase = true)
    }

    private fun typeRank(entry: RecordEntry): Int = when (entry) {
        is RecordEntry.CareNoteEntry -> TYPE_RANK_CARE_NOTE
        is RecordEntry.DiaryEntry -> TYPE_RANK_DIARY
    }

    private fun idOf(entry: RecordEntry): Long = when (entry) {
        is RecordEntry.CareNoteEntry -> entry.row.note.id
        is RecordEntry.DiaryEntry -> entry.log.id
    }

    /** 照护笔记模式沿用既有排序，混排视图另行排序：两套排序刻意不同（见类注释）。 */
    fun keepsExistingCareNoteOrder(mode: RecordsMode): Boolean = mode == RecordsMode.CARE_NOTES
}
