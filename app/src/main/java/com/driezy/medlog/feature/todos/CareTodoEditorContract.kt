package com.driezy.medlog.feature.todos

import com.driezy.medlog.data.model.CareNote

/** 编辑器草稿：与 [com.driezy.medlog.data.model.CareTodo] 对齐但不含身份/状态字段。 */
data class CareTodoDraft(
    val title: String = "",
    val dueAtMs: Long? = null,
    val sourceNote: String = "",
    /** 只读来源元数据（若从观察/症状生成时会带上；本期仅展示，不解读）。 */
    val sourceType: String? = null,
    val sourceId: Long? = null,
)

/** 保存前校验失败原因；null 表示通过（标题必填）。 */
enum class CareTodoValidationError { EMPTY_TITLE, }

data class CareTodoEditorUiState(
    val draft: CareTodoDraft = CareTodoDraft(),
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val validationError: CareTodoValidationError? = null,
    /** 就地「相关笔记」：以该待办为 target 的 CareNote（空则不渲染，docs/care-notes.md §7）。 */
    val relatedNotes: List<CareNote> = emptyList(),
)

sealed interface CareTodoEditorUiAction {
    data class LoadExisting(val todoId: Long) : CareTodoEditorUiAction

    data class TitleChanged(val title: String) : CareTodoEditorUiAction

    data class DueChanged(val dueAtMs: Long?) : CareTodoEditorUiAction

    data class SourceNoteChanged(val note: String) : CareTodoEditorUiAction

    data object Save : CareTodoEditorUiAction
}

sealed interface CareTodoEditorUiEffect {
    data object Saved : CareTodoEditorUiEffect

    data class Failed(val message: String?) : CareTodoEditorUiEffect
}
