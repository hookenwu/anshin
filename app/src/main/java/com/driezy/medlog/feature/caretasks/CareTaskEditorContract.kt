package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.TimePeriod

/**
 * 编辑器草稿：与 [com.driezy.medlog.data.model.CareTask] 形状对齐但不含身份字段。
 * 时间以 `HH:mm` 字符串列表保存（最终逗号拼接进 reminderTimes 列）。
 */
data class CareTaskDraft(
    val title: String = "",
    val category: String = CareTaskCategory.OTHER,
    val completionMode: CareTaskCompletionMode = CareTaskCompletionMode.TOGGLE,
    val defaultDurationMinutes: Int = 30,
    val scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
    val timePeriods: Set<TimePeriod> = emptySet(),
    val reminderTimes: List<String> = emptyList(),
    val intervalHours: Int = 2,
    val frequencyType: String = FREQUENCY_DAILY,
    val frequencyInterval: Int = 2,
    val frequencyDays: Set<Int> = emptySet(),
    val startDate: Long = 0L,
    val notes: String = "",
) {
    companion object {
        const val FREQUENCY_DAILY = "daily"
        const val FREQUENCY_INTERVAL = "interval"
        const val FREQUENCY_SPECIFIC_DAYS = "specific_days"
    }
}

/** 保存前的校验失败原因；null 表示通过。 */
enum class CareTaskValidationError { EMPTY_TITLE, MISSING_TIME, MISSING_INTERVAL }

data class CareTaskEditorUiState(
    val draft: CareTaskDraft = CareTaskDraft(),
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val validationError: CareTaskValidationError? = null,
)

sealed interface CareTaskEditorUiAction {
    data class LoadExisting(val taskId: Long) : CareTaskEditorUiAction

    data class TitleChanged(val title: String) : CareTaskEditorUiAction

    data class CategoryChanged(val category: String) : CareTaskEditorUiAction

    data class CompletionModeChanged(val mode: CareTaskCompletionMode) : CareTaskEditorUiAction

    data class DefaultMinutesChanged(val minutes: Int) : CareTaskEditorUiAction

    data class ScheduleKindChanged(val kind: CareTaskScheduleKind) : CareTaskEditorUiAction

    data class ToggleTimePeriod(val period: TimePeriod) : CareTaskEditorUiAction

    /** 归一化后的 `HH:mm`；非法或重复输入被忽略。 */
    data class AddTime(val raw: String) : CareTaskEditorUiAction

    data class RemoveTime(val hhmm: String) : CareTaskEditorUiAction

    data class IntervalHoursChanged(val hours: Int) : CareTaskEditorUiAction

    data class FrequencyChanged(val type: String) : CareTaskEditorUiAction

    data class FrequencyIntervalChanged(val days: Int) : CareTaskEditorUiAction

    data class ToggleWeekday(val dayOfWeek: Int) : CareTaskEditorUiAction

    data class StartDateChanged(val epochMs: Long) : CareTaskEditorUiAction

    data class NotesChanged(val notes: String) : CareTaskEditorUiAction

    data object Save : CareTaskEditorUiAction

    data object Archive : CareTaskEditorUiAction

    data object Delete : CareTaskEditorUiAction
}

sealed interface CareTaskEditorUiEffect {
    data object Saved : CareTaskEditorUiEffect

    data object NavigateBack : CareTaskEditorUiEffect

    data class Failed(val message: String?) : CareTaskEditorUiEffect
}
