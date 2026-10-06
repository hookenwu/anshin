package com.driezy.medlog.feature.todos

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.repository.CareTodoRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 待办列表页 ViewModel：进行中 / 历史两个作用域直接 collect 仓库流，tab 只是前端选择。
 * 完成 / 取消 / 重新打开全部经仓库收口（幂等、撤销清空 closedAtMs）。
 */
@HiltViewModel
class CareTodosViewModel @Inject constructor(private val repository: CareTodoRepository) : BaseViewModel() {

    private val selectedTab = MutableStateFlow(CareTodosTab.ACTIVE)
    private val savingIds = MutableStateFlow<Set<Long>>(emptySet())
    private val busyIds = mutableSetOf<Long>()

    private val effectChannel = Channel<CareTodosUiEffect>(Channel.BUFFERED)
    val uiEffect = effectChannel.receiveAsFlow()

    val uiState = combine(
        repository.getOpenTodos(),
        repository.getHistoryTodos(),
        selectedTab,
        savingIds,
    ) { active, history, tab, saving ->
        CareTodosUiState(
            activeTodos = active,
            historyTodos = history,
            selectedTab = tab,
            isLoading = false,
            savingIds = saving,
        )
    }
        .catch { error ->
            effectChannel.send(CareTodosUiEffect.Failed(error.localizedMessage))
            emit(CareTodosUiState(isLoading = false, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareTodosUiState())

    fun onAction(action: CareTodosUiAction) {
        when (action) {
            is CareTodosUiAction.SetTab -> selectedTab.value = action.tab
            is CareTodosUiAction.Complete -> runCommand(action.todoId) { repository.complete(it) }
            is CareTodosUiAction.Cancel -> runCommand(action.todoId) { repository.cancel(it) }
            is CareTodosUiAction.Reopen -> runCommand(action.todoId) { repository.reopen(it) }
            CareTodosUiAction.Refresh -> Unit // 仓库流自身即实时来源，无需手动刷新
        }
    }

    private fun runCommand(todoId: Long, command: suspend (Long) -> Unit) {
        if (!busyIds.add(todoId)) return
        savingIds.value = busyIds.toSet()
        safeLaunch(
            onError = { error ->
                busyIds.remove(todoId)
                savingIds.value = busyIds.toSet()
                effectChannel.trySend(CareTodosUiEffect.Failed(error.localizedMessage))
            },
        ) {
            try {
                command(todoId)
            } finally {
                busyIds.remove(todoId)
                savingIds.value = busyIds.toSet()
            }
        }
    }
}
