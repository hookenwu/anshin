package com.driezy.medlog.feature.todos

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.repository.CareTodoRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** 校验规则（UI 与单测共用）：标题非空即可；截止与来源备注都可选。 */
internal fun validateCareTodoDraft(draft: CareTodoDraft): CareTodoValidationError? =
    if (draft.title.trim().isEmpty()) CareTodoValidationError.EMPTY_TITLE else null

/**
 * 待办编辑器（新建 + 编辑）。命令入口仍是仓库（写入前按当前成员盖章；本 VM 只收集草稿并校验）。
 */
@HiltViewModel
class CareTodoEditorViewModel @Inject constructor(private val repository: CareTodoRepository) : BaseViewModel() {

    private val _uiState = MutableStateFlow(CareTodoEditorUiState())
    val uiState = _uiState.asStateFlow()

    private val effectChannel = Channel<CareTodoEditorUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var existing: CareTodo? = null

    fun onAction(action: CareTodoEditorUiAction) {
        when (action) {
            is CareTodoEditorUiAction.LoadExisting -> loadExisting(action.todoId)
            is CareTodoEditorUiAction.TitleChanged -> editDraft { it.copy(title = action.title) }
            is CareTodoEditorUiAction.DueChanged -> editDraft { it.copy(dueAtMs = action.dueAtMs) }
            is CareTodoEditorUiAction.SourceNoteChanged -> editDraft { it.copy(sourceNote = action.note) }
            CareTodoEditorUiAction.Save -> save()
        }
    }

    private fun loadExisting(todoId: Long) {
        safeLaunch(onError = { error ->
            effectChannel.trySend(CareTodoEditorUiEffect.Failed(error.localizedMessage))
        }) {
            val todo = repository.getTodoById(todoId)
            if (todo == null) {
                effectChannel.send(CareTodoEditorUiEffect.Failed("care_todo_not_found"))
                return@safeLaunch
            }
            existing = todo
            _uiState.update {
                it.copy(
                    isEditing = true,
                    isLoading = false,
                    draft = CareTodoDraft(
                        title = todo.title,
                        dueAtMs = todo.dueAtMs,
                        sourceNote = todo.sourceNote.orEmpty(),
                        sourceType = todo.sourceType,
                        sourceId = todo.sourceId,
                    ),
                )
            }
        }
    }

    private fun save() {
        val draft = _uiState.value.draft
        val error = validateCareTodoDraft(draft)
        if (error != null) {
            _uiState.update { it.copy(validationError = error) }
            return
        }
        safeLaunch(onError = { failure ->
            _uiState.update { it.copy(isSaving = false) }
            effectChannel.trySend(CareTodoEditorUiEffect.Failed(failure.localizedMessage))
        }) {
            _uiState.update { it.copy(isSaving = true, validationError = null) }
            try {
                val note = draft.sourceNote.trim().ifEmpty { null }
                val current = existing
                if (current == null) {
                    repository.createTodo(
                        title = draft.title,
                        dueAtMs = draft.dueAtMs,
                        sourceType = draft.sourceType,
                        sourceId = draft.sourceId,
                        sourceNote = note,
                    )
                } else {
                    repository.updateTodo(
                        id = current.id,
                        title = draft.title,
                        dueAtMs = draft.dueAtMs,
                        sourceNote = note,
                    )
                }
                effectChannel.send(CareTodoEditorUiEffect.Saved)
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun editDraft(block: (CareTodoDraft) -> CareTodoDraft) {
        _uiState.update { it.copy(draft = block(it.draft), validationError = null) }
    }
}
