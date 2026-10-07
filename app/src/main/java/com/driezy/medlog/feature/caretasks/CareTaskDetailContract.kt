package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.HealthType

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
    /** 就地「相关笔记」：该照护事项为 target 的 CareNote（空则不渲染）。 */
    val relatedNotes: List<CareNote> = emptyList(),
)

sealed interface CareTaskDetailUiAction {
    data class Load(val taskId: Long) : CareTaskDetailUiAction

    /**
     * 完成本次。[postureNote] 是可选的体位（左/右/平卧），落在 `CareTaskLog`；
     * [notes] 是本次备注。二者都可不填，走既有的「一键打卡」语义。
     */
    data class Complete(val scheduledTimeMs: Long, val postureNote: String? = null, val notes: String = "") :
        CareTaskDetailUiAction

    /** T8：记录一次过程数据（血氧 / 氧流量 / 读数次数）到健康表。 */
    data class RecordMeasurement(
        val scheduledTimeMs: Long,
        val type: HealthType,
        val value: Double,
        val secondaryValue: Double? = null,
        val notes: String = "",
    ) : CareTaskDetailUiAction

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

    /** 过程数据已写入健康表。 */
    data object MeasurementRecorded : CareTaskDetailUiEffect

    /** 该时间槽的该指标已记录过，未重复写入。 */
    data object MeasurementDuplicate : CareTaskDetailUiEffect
}
