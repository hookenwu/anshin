package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.data.repository.CareNoteTarget
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.data.repository.CareTodoRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** 校验规则（UI 与单测共用）：标题、正文都必填。 */
internal fun validateCareNoteDraft(draft: CareNoteDraft): CareNoteValidationError? = when {
    draft.title.trim().isEmpty() -> CareNoteValidationError.EMPTY_TITLE
    draft.body.trim().isEmpty() -> CareNoteValidationError.EMPTY_BODY
    else -> null
}

/**
 * 照护笔记编辑器（新建 + 编辑）。命令入口仍是仓库（写入前按当前成员盖章；本 VM 只收集草稿并校验）。
 *
 * 归属类型默认 `PERSONAL_OBSERVATION`（最保守），但**不会**强制用户显式选择——而是给强引导；
 * 类型在任何展示面恒可见。状态默认 `ACTIVE`，只有用户能改。
 */
@HiltViewModel
class CareNoteEditorViewModel @Inject constructor(
    private val careNoteRepository: CareNoteRepository,
    private val medicationRepository: MedicationRepository,
    private val careTaskRepository: CareTaskRepository,
    private val careTodoRepository: CareTodoRepository,
    private val activeRecipient: ActiveRecipientStore,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(CareNoteEditorUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    private val effectChannel = Channel<CareNoteEditorUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var existing: CareNote? = null
    private var optionsLoaded = false

    init {
        // 新建时也要能选择挂接目标：进入编辑器即加载可选项（与是否编辑无关）。
        ensureOptions()
    }

    fun onAction(action: CareNoteEditorUiAction) {
        when (action) {
            is CareNoteEditorUiAction.LoadExisting -> loadExisting(action.noteId)
            is CareNoteEditorUiAction.TitleChanged -> editDraft { it.copy(title = action.title) }
            is CareNoteEditorUiAction.BodyChanged -> editDraft { it.copy(body = action.body) }
            is CareNoteEditorUiAction.AttributionTypeChanged -> editDraft { it.copy(attributionType = action.type) }
            is CareNoteEditorUiAction.AttributionNameChanged -> editDraft { it.copy(attributionName = action.name) }
            is CareNoteEditorUiAction.AttributionAtChanged -> editDraft { it.copy(attributionAtMs = action.atMs) }
            is CareNoteEditorUiAction.AttributionTextChanged -> editDraft { it.copy(attributionText = action.text) }
            is CareNoteEditorUiAction.StatusChanged -> editDraft { it.copy(status = action.status) }
            is CareNoteEditorUiAction.SupersededTextChanged -> editDraft { it.copy(supersededText = action.text) }
            is CareNoteEditorUiAction.ToggleLink -> toggleLink(action.target)
            is CareNoteEditorUiAction.PreloadQuickAddLink -> preloadQuickAddLink(action.targetType, action.targetId)
            CareNoteEditorUiAction.Save -> save()
            CareNoteEditorUiAction.Delete -> delete()
        }
    }

    private fun loadExisting(noteId: Long) {
        ensureOptions()
        safeLaunch(onError = { error ->
            _uiState.update { it.copy(isLoading = false) }
            effectChannel.trySend(CareNoteEditorUiEffect.Failed(error.localizedMessage))
        }) {
            val note = careNoteRepository.getNoteById(noteId)
            if (note == null) {
                _uiState.update { it.copy(isLoading = false) }
                effectChannel.send(CareNoteEditorUiEffect.Failed("care_note_not_found"))
                return@safeLaunch
            }
            existing = note
            val links = careNoteRepository.linksForNote(noteId)
                .map { CareNoteTarget(it.targetType, it.targetId) }
                .toSet()
            addMissingOptions(links)
            _uiState.update {
                it.copy(
                    isEditing = true,
                    isLoading = false,
                    draft = CareNoteDraft(
                        title = note.title,
                        body = note.body,
                        attributionType = note.attributionType,
                        attributionName = note.attributionName.orEmpty(),
                        attributionAtMs = note.attributionAtMs,
                        attributionText = note.attributionText.orEmpty(),
                        status = note.status,
                        supersededText = note.supersededText.orEmpty(),
                        links = links,
                    ),
                )
            }
        }
    }

    private fun save() {
        val draft = _uiState.value.draft
        val error = validateCareNoteDraft(draft)
        if (error != null) {
            _uiState.update { it.copy(validationError = error) }
            return
        }
        safeLaunch(onError = { failure ->
            _uiState.update { it.copy(isSaving = false) }
            effectChannel.trySend(CareNoteEditorUiEffect.Failed(failure.localizedMessage))
        }) {
            _uiState.update { it.copy(isSaving = true, validationError = null) }
            try {
                val name = draft.attributionName.ifBlank { null }
                val text = draft.attributionText.ifBlank { null }
                val superseded = draft.supersededText.ifBlank { null }
                val current = existing
                if (current == null) {
                    val id = careNoteRepository.createNote(
                        title = draft.title,
                        body = draft.body,
                        attributionType = draft.attributionType,
                        attributionName = name,
                        attributionAtMs = draft.attributionAtMs,
                        attributionText = text,
                        links = draft.links.toList(),
                    )
                    if (draft.status != CareNoteStatus.ACTIVE) {
                        careNoteRepository.setStatus(id, draft.status, superseded)
                    }
                } else {
                    careNoteRepository.updateNote(
                        id = current.id,
                        title = draft.title,
                        body = draft.body,
                        attributionType = draft.attributionType,
                        attributionName = name,
                        attributionAtMs = draft.attributionAtMs,
                        attributionText = text,
                        links = draft.links.toList(),
                    )
                    if (draft.status != current.status || draft.status == CareNoteStatus.SUPERSEDED) {
                        careNoteRepository.setStatus(current.id, draft.status, superseded)
                    }
                }
                effectChannel.send(CareNoteEditorUiEffect.Saved)
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /** 删除当前编辑的笔记；成功后离开编辑器（不得停留在已不存在的笔记上）。 */
    private fun delete() {
        val current = existing ?: return
        safeLaunch(onError = { error ->
            effectChannel.trySend(CareNoteEditorUiEffect.Failed(error.localizedMessage))
        }) {
            careNoteRepository.deleteNote(current.id)
            effectChannel.send(CareNoteEditorUiEffect.NavigateBack)
        }
    }

    private fun ensureOptions() {
        if (optionsLoaded) return
        optionsLoaded = true
        safeLaunch(onError = { _uiState.update { it.copy(isLoading = false) } }) {
            val medications = medicationRepository.getActiveOnce().map {
                CareNoteLinkOption(CareNoteTargetType.MEDICATION, it.id, it.name)
            }
            val tasks = careTaskRepository.getActiveTasksOnce().map {
                CareNoteLinkOption(CareNoteTargetType.CARE_TASK, it.id, it.title)
            }
            val todos = careTodoRepository.getOpenTodosOnce().map {
                CareNoteLinkOption(CareNoteTargetType.TODO, it.id, it.title)
            }
            // 成功路径必须清除加载态：否则 MainScreenChrome 只渲染转圈，表单永不出现（无终点）。
            _uiState.update { it.copy(linkOptions = medications + tasks + todos, isLoading = false) }
        }
    }

    /** 已关联但不在可选项中的目标（如已归档）：补一个占位项，避免保存时静默丢失关联。 */
    private fun addMissingOptions(targets: Set<CareNoteTarget>) {
        val known = _uiState.value.linkOptions.map { it.targetType to it.targetId }.toSet()
        val missing = targets.filter { (it.targetType to it.targetId) !in known }
        if (missing.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                linkOptions = state.linkOptions + missing.map {
                    CareNoteLinkOption(it.targetType, it.targetId, label = "")
                },
            )
        }
    }

    private fun toggleLink(target: CareNoteTarget) {
        editDraft { draft ->
            draft.copy(
                links = if (target in draft.links) draft.links - target else draft.links + target,
            )
        }
    }

    /**
     * 上下文快捷新增的成员校验（硬规则，docs/record-center-spec.md §3 D4）：
     * 仅当目标 `careRecipientId` **等于**当前 [ActiveRecipientStore] 成员时才预挂关联；
     * 不一致时**不预挂**并给出中性提示，**绝不写入跨成员关联**。
     * 无当前成员（`NO_RECIPIENT`）时按既有约定不走写入路径（读空、写抛错），同样不预挂。
     */
    private fun preloadQuickAddLink(targetType: String, targetId: Long) {
        safeLaunch(onError = { _uiState.update { it.copy(quickAddRefused = true) } }) {
            val ownerId = when (targetType) {
                CareNoteTargetType.MEDICATION -> medicationRepository.getMedicationById(targetId)?.careRecipientId
                CareNoteTargetType.CARE_TASK -> careTaskRepository.getTaskById(targetId)?.careRecipientId
                CareNoteTargetType.TODO -> careTodoRepository.getTodoById(targetId)?.careRecipientId
                else -> null
            }
            val activeId = activeRecipient.current()
            val belongsToActiveMember = ownerId != null &&
                activeId != ActiveRecipientStore.NO_RECIPIENT &&
                ownerId == activeId
            if (!belongsToActiveMember) {
                _uiState.update { it.copy(quickAddRefused = true, isLoading = false) }
                return@safeLaunch
            }
            val target = CareNoteTarget(targetType, targetId)
            addMissingOptions(setOf(target))
            editDraft { it.copy(links = it.links + target) }
            _uiState.update { it.copy(quickAddRefused = false) }
        }
    }

    private fun editDraft(block: (CareNoteDraft) -> CareNoteDraft) {
        _uiState.update { it.copy(draft = block(it.draft), validationError = null, isLoading = false) }
    }
}
