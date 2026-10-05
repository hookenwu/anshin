package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTask

/**
 * 照护事项列表页 UI 状态。
 *
 * 活跃与已归档两组都由仓库按「当前成员」作用域产出（成员切换即重发），
 * [todayStatus] 只对固定时刻型给出当前时间槽的完成态；[visibleTasks] 由筛选派生。
 */
data class CareTasksUiState(
    val activeTasks: List<CareTask> = emptyList(),
    val archivedTasks: List<CareTask> = emptyList(),
    val showArchived: Boolean = false,
    val todayStatus: Map<Long, CareTaskTodayStatus> = emptyMap(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
) {
    val visibleTasks: List<CareTask> get() = if (showArchived) archivedTasks else activeTasks
}

sealed interface CareTasksUiAction {
    data class SetShowArchived(val show: Boolean) : CareTasksUiAction

    data object Refresh : CareTasksUiAction
}

sealed interface CareTasksUiEffect {
    data class Failed(val message: String?) : CareTasksUiEffect
}
