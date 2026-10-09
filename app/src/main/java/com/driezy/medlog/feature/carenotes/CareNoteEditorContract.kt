package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CarePerson
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
    /**
     * 可选关联的人员（docs/care-people.md §4.1）。选中人员后填写；**手动改姓名即自动解除关联**
     * （清空本字段）并保留用户新输入的姓名（边界规则①，用户的手改优先）。
     */
    val attributionPersonId: Long? = null,
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
    /** 选择器：当前成员中按姓名匹配的已有人员（选择即填写，选中不改变归属类型）。 */
    val personOptions: List<CarePerson> = emptyList(),
    /**
     * 上下文快捷新增的成员校验结果：目标不属于当前成员时置为 true，
     * **不预挂**跨成员关联，仅给出中性提示（docs/record-center-spec.md §3 D4 硬规则）。
     */
    val quickAddRefused: Boolean = false,
)

sealed interface CareNoteEditorUiAction {
    data class LoadExisting(val noteId: Long) : CareNoteEditorUiAction

    data class TitleChanged(val title: String) : CareNoteEditorUiAction

    data class BodyChanged(val body: String) : CareNoteEditorUiAction

    data class AttributionTypeChanged(val type: String) : CareNoteEditorUiAction

    data class AttributionNameChanged(val name: String) : CareNoteEditorUiAction

    /**
     * 从选择器选中一名人员：填写其姓名并关联人员 id。
     * **不设置、不锁定 `attributionType`**（类型只属于笔记，docs/care-people.md §3）。
     */
    data class AttributionPersonSelected(val personId: Long, val name: String) : CareNoteEditorUiAction

    /**
     *「＋ 新增人员」快速新增：只填姓名（必填）→ 保存后**自动选中**并回到笔记编辑流程，
     * 不离开编辑器（docs/care-people.md §4.1、原则 6）。
     */
    data class QuickAddPerson(val name: String) : CareNoteEditorUiAction

    data class AttributionAtChanged(val atMs: Long?) : CareNoteEditorUiAction

    data class AttributionTextChanged(val text: String) : CareNoteEditorUiAction

    data class StatusChanged(val status: String) : CareNoteEditorUiAction

    data class SupersededTextChanged(val text: String) : CareNoteEditorUiAction

    data class ToggleLink(val target: CareNoteTarget) : CareNoteEditorUiAction

    /**
     * 上下文快捷新增：请求把某目标（药/照护事项/待办）预挂到新笔记。
     * 校验该目标 `careRecipientId == 当前成员`；不一致时不预挂并给出中性提示，绝不写跨成员关联。
     */
    data class PreloadQuickAddLink(val targetType: String, val targetId: Long) : CareNoteEditorUiAction

    data object Save : CareNoteEditorUiAction

    /** 删除正在编辑的笔记（破坏性；仅编辑模式可用）。 */
    data object Delete : CareNoteEditorUiAction
}

sealed interface CareNoteEditorUiEffect {
    data object Saved : CareNoteEditorUiEffect

    /** 删除成功后离开编辑器（不得停留在已不存在的笔记上）。 */
    data object NavigateBack : CareNoteEditorUiEffect

    data class Failed(val message: String?) : CareNoteEditorUiEffect
}
