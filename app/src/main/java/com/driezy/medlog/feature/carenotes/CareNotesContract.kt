package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.repository.CareNoteWithState

/**
 * 照护笔记列表页 UI 状态（docs/care-notes.md §6）。
 *
 * 行数据由仓库按「当前成员」作用域产出（[CareNoteWithState] 携带悬挂关联）。
 * [query] 为空 → 普通列表（`ACTIVE` + `QUESTIONABLE`，`SUPERSEDED` 折叠）；非空 → 主动搜索
 * （命中的 `SUPERSEDED` 也会返回，由展示层标「已被更新」）。
 */
data class CareNotesUiState(
    val notes: List<CareNoteWithState> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val savingIds: Set<Long> = emptySet(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

sealed interface CareNotesUiAction {
    data class QueryChanged(val query: String) : CareNotesUiAction

    /** 状态切换：**只有用户能设置状态**（App 不推断、不自动过期）。 */
    data class MarkQuestionable(val noteId: Long) : CareNotesUiAction

    data class MarkActive(val noteId: Long) : CareNotesUiAction

    data class MarkSuperseded(val noteId: Long, val supersededText: String? = null) : CareNotesUiAction

    /** 一键清除该笔记的悬挂关联（目标已不存在）；无后台清理。 */
    data class RemoveDanglingLinks(val noteId: Long) : CareNotesUiAction

    data class Delete(val noteId: Long) : CareNotesUiAction

    data object Refresh : CareNotesUiAction
}

sealed interface CareNotesUiEffect {
    data class Failed(val message: String?) : CareNotesUiEffect
}
