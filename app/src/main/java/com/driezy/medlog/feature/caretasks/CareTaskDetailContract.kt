package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus

/**
 * 今日一次排期在详情页的呈现：时间标签、当前记录状态与（进行中时的）已用时长。
 * 按需型没有固定时刻，[timeLabel] 为空串，由 UI 显示「按需」。
 */
data class CareTaskOccurrenceUi(
    val scheduledTimeMs: Long,
    val timeLabel: String,
    val status: CareTaskLogStatus?,
    val elapsedMinutes: Int? = null,
)

/** 照护事项详情页 UI 状态。 */
data class CareTaskDetailUiState(
    val task: CareTask? = null,
    val occurrences: List<CareTaskOccurrenceUi> = emptyList(),
    val recentLogs: List<CareTaskLog> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val isSaving: Boolean = false,
)

sealed interface CareTaskDetailUiAction {
    data class Load(val taskId: Long) : CareTaskDetailUiAction

    data class Complete(val scheduledTimeMs: Long) : CareTaskDetailUiAction

    data class Start(val scheduledTimeMs: Long) : CareTaskDetailUiAction

    data class Skip(val scheduledTimeMs: Long) : CareTaskDetailUiAction

    data class Undo(val scheduledTimeMs: Long) : CareTaskDetailUiAction

    data object Archive : CareTaskDetailUiAction

    data object Delete : CareTaskDetailUiAction

    data object RefreshTime : CareTaskDetailUiAction
}

sealed interface CareTaskDetailUiEffect {
    data object NavigateBack : CareTaskDetailUiEffect

    data class Failed(val message: String?) : CareTaskDetailUiEffect
}
