package com.driezy.medlog.feature.carenotes

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 照护笔记列表页 ViewModel：搜索词经 [flatMapLatest] 驱动仓库查询（默认列表 / 主动搜索）。
 * 状态切换、悬挂关联清除、删除全部经仓库收口（**只有用户能设置状态**）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CareNotesViewModel @Inject constructor(private val repository: CareNoteRepository) : BaseViewModel() {

    private val query = MutableStateFlow("")
    private val savingIds = MutableStateFlow<Set<Long>>(emptySet())
    private val busyIds = mutableSetOf<Long>()

    private val effectChannel = Channel<CareNotesUiEffect>(Channel.BUFFERED)
    val uiEffect = effectChannel.receiveAsFlow()

    private val rows = query.flatMapLatest { current -> repository.observeNotes(current) }

    val uiState = combine(rows, query, savingIds) { notes, current, saving ->
        CareNotesUiState(notes = notes, query = current, isLoading = false, savingIds = saving)
    }
        .catch { error ->
            effectChannel.send(CareNotesUiEffect.Failed(error.localizedMessage))
            emit(CareNotesUiState(isLoading = false, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareNotesUiState())

    fun onAction(action: CareNotesUiAction) {
        when (action) {
            is CareNotesUiAction.QueryChanged -> query.value = action.query
            is CareNotesUiAction.MarkQuestionable -> runCommand(action.noteId) {
                repository.setStatus(action.noteId, CareNoteStatus.QUESTIONABLE)
            }
            is CareNotesUiAction.MarkActive -> runCommand(action.noteId) {
                repository.setStatus(action.noteId, CareNoteStatus.ACTIVE)
            }
            is CareNotesUiAction.MarkSuperseded -> runCommand(action.noteId) {
                repository.setStatus(action.noteId, CareNoteStatus.SUPERSEDED, action.supersededText)
            }
            is CareNotesUiAction.RemoveDanglingLinks -> runCommand(action.noteId) {
                repository.removeDanglingLinks(action.noteId)
            }
            is CareNotesUiAction.Delete -> runCommand(action.noteId) { repository.deleteNote(action.noteId) }
            CareNotesUiAction.Refresh -> Unit // 仓库流自身即实时来源，无需手动刷新
        }
    }

    private fun runCommand(noteId: Long, command: suspend (Long) -> Unit) {
        if (!busyIds.add(noteId)) return
        savingIds.value = busyIds.toSet()
        safeLaunch(
            onError = { error ->
                busyIds.remove(noteId)
                savingIds.value = busyIds.toSet()
                effectChannel.trySend(CareNotesUiEffect.Failed(error.localizedMessage))
            },
        ) {
            try {
                command(noteId)
            } finally {
                busyIds.remove(noteId)
                savingIds.value = busyIds.toSet()
            }
        }
    }
}
