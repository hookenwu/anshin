package com.driezy.medlog.feature.todos

import com.driezy.medlog.data.model.CareTodo

/** 待办列表页的两个 tab：进行中（未闭环）/ 历史（DONE + CANCELLED）。 */
enum class CareTodosTab { ACTIVE, HISTORY }

/**
 * 待办列表页 UI 状态。
 *
 * 两组都由仓库按「当前成员」作用域产出（成员切换即重发），[visibleTodos] 由当前 tab 派生。
 */
data class CareTodosUiState(
    val activeTodos: List<CareTodo> = emptyList(),
    val historyTodos: List<CareTodo> = emptyList(),
    val selectedTab: CareTodosTab = CareTodosTab.ACTIVE,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val savingIds: Set<Long> = emptySet(),
) {
    val visibleTodos: List<CareTodo> get() = if (selectedTab == CareTodosTab.ACTIVE) activeTodos else historyTodos
}

sealed interface CareTodosUiAction {
    data class SetTab(val tab: CareTodosTab) : CareTodosUiAction

    data class Complete(val todoId: Long) : CareTodosUiAction

    /** 取消（不再处理）；历史 tab 的撤销入口是 [Reopen]。 */
    data class Cancel(val todoId: Long) : CareTodosUiAction

    data class Reopen(val todoId: Long) : CareTodosUiAction

    data object Refresh : CareTodosUiAction
}

sealed interface CareTodosUiEffect {
    data class Failed(val message: String?) : CareTodosUiEffect
}
