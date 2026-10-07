package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.repository.CareNoteTarget

/** 可挂接目标（药/照护事项/待办）的展示项；[label] 为目标名称。 */
data class CareNoteLinkOption(val targetType: String, val targetId: Long, val label: String)

/** 编辑器草稿：与 [com.driezy.medlog.data.model.CareNote] 对齐但不含身份/时间戳字段。 */
data class CareNoteDraft(
    val title: String = "",
    val body: String = "",
    /** 归属类型，默认最保守的「个人观察」。 */
    val attributionType: String = CareNoteAttributionType.PERSONAL_OBSERVATION,
    val attributionName: String = "",
    val attributionAtMs: Long? = null,
    val attributionText: String = "",
    /** 状态，默认 ACTIVE；只有用户能改。 */
    val status: String = CareNoteStatus.ACTIVE,
    val supersededText: String = "",
    /** 挂接目标（多选）；不含「成员」，成员归属由 careRecipientId 决定。 */
    val links: Set<CareNoteTarget> = emptySet(),
)

/** 保存前校验失败原因；null 表示通过（标题、正文必填）。 */
enum class CareNoteValidationError { EMPTY_TITLE, EMPTY_BODY }

data class CareNoteEditorUiState(
    val draft: CareNoteDraft = CareNoteDraft(),
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val validationError: CareNoteValidationError? = null,
    val linkOptions: List<CareNoteLinkOption> = emptyList(),
)

sealed interface CareNoteEditorUiAction {
    data class LoadExisting(val noteId: Long) : CareNoteEditorUiAction

    data class TitleChanged(val title: String) : CareNoteEditorUiAction

    data class BodyChanged(val body: String) : CareNoteEditorUiAction

    data class AttributionTypeChanged(val type: String) : CareNoteEditorUiAction

    data class AttributionNameChanged(val name: String) : CareNoteEditorUiAction

    data class AttributionAtChanged(val atMs: Long?) : CareNoteEditorUiAction

    data class AttributionTextChanged(val text: String) : CareNoteEditorUiAction

    data class StatusChanged(val status: String) : CareNoteEditorUiAction

    data class SupersededTextChanged(val text: String) : CareNoteEditorUiAction

    data class ToggleLink(val target: CareNoteTarget) : CareNoteEditorUiAction

    data object Save : CareNoteEditorUiAction
}

sealed interface CareNoteEditorUiEffect {
    data object Saved : CareNoteEditorUiEffect

    data class Failed(val message: String?) : CareNoteEditorUiEffect
}
