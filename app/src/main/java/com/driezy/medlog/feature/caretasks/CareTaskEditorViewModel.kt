package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.TimePeriods
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import javax.inject.Inject

/** 编辑器的校验规则（保存前统一判定，UI 与单测共用）。 */
internal fun validateCareTaskDraft(draft: CareTaskDraft): CareTaskValidationError? {
    if (draft.title.trim().isEmpty()) return CareTaskValidationError.EMPTY_TITLE
    return when (draft.scheduleKind) {
        CareTaskScheduleKind.FIXED_TIMES ->
            if (draft.reminderTimes.isEmpty() && draft.timePeriods.isEmpty()) {
                CareTaskValidationError.MISSING_TIME
            } else {
                null
            }
        CareTaskScheduleKind.INTERVAL ->
            if (draft.intervalHours <= 0) CareTaskValidationError.MISSING_INTERVAL else null
        CareTaskScheduleKind.AS_NEEDED -> null
    }
}

/**
 * 照护事项编辑器（新建 + 编辑）。
 *
 * 命令入口仍是仓库（写入前由仓库按当前成员盖章）；本 VM 只负责草稿收集、校验与身份字段拼接。
 */
@HiltViewModel
class CareTaskEditorViewModel @Inject constructor(
    private val repository: CareTaskRepository,
    private val clock: Clock,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(
        CareTaskEditorUiState(draft = CareTaskDraft(startDate = clock.millis())),
    )
    val uiState = _uiState.asStateFlow()

    private val effectChannel = Channel<CareTaskEditorUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var existing: CareTask? = null

    fun onAction(action: CareTaskEditorUiAction) {
        when (action) {
            is CareTaskEditorUiAction.LoadExisting -> loadExisting(action.taskId)
            is CareTaskEditorUiAction.TitleChanged -> editDraft { it.copy(title = action.title) }
            is CareTaskEditorUiAction.CategoryChanged -> editDraft { it.copy(category = action.category) }
            is CareTaskEditorUiAction.CompletionModeChanged -> editDraft { it.copy(completionMode = action.mode) }
            is CareTaskEditorUiAction.DefaultMinutesChanged ->
                editDraft { it.copy(defaultDurationMinutes = action.minutes.coerceIn(1, MAX_DURATION_MINUTES)) }
            is CareTaskEditorUiAction.ScheduleKindChanged -> editDraft { it.copy(scheduleKind = action.kind) }
            is CareTaskEditorUiAction.ToggleTimePeriod -> editDraft { draft ->
                draft.copy(timePeriods = draft.togglePeriod(action.period))
            }
            is CareTaskEditorUiAction.AddTime -> addTime(action.raw)
            is CareTaskEditorUiAction.RemoveTime -> editDraft {
                it.copy(reminderTimes = it.reminderTimes - action.hhmm)
            }
            is CareTaskEditorUiAction.IntervalHoursChanged -> editDraft { it.copy(intervalHours = action.hours) }
            is CareTaskEditorUiAction.FrequencyChanged -> editDraft { it.copy(frequencyType = action.type.lowercase()) }
            is CareTaskEditorUiAction.FrequencyIntervalChanged ->
                editDraft { it.copy(frequencyInterval = action.days.coerceAtLeast(1)) }
            is CareTaskEditorUiAction.ToggleWeekday -> editDraft { draft ->
                draft.copy(frequencyDays = draft.toggleWeekday(action.dayOfWeek))
            }
            is CareTaskEditorUiAction.StartDateChanged -> editDraft { it.copy(startDate = action.epochMs) }
            is CareTaskEditorUiAction.NotesChanged -> editDraft { it.copy(notes = action.notes) }
            CareTaskEditorUiAction.Save -> save()
            CareTaskEditorUiAction.Archive -> toggleArchive()
            CareTaskEditorUiAction.Delete -> delete()
        }
    }

    private fun loadExisting(taskId: Long) {
        safeLaunch(onError = { error ->
            effectChannel.trySend(CareTaskEditorUiEffect.Failed(error.localizedMessage))
        }) {
            val task = repository.getTaskById(taskId)
            if (task == null) {
                effectChannel.send(CareTaskEditorUiEffect.Failed("care_task_not_found"))
                return@safeLaunch
            }
            existing = task
            _uiState.update { it.copy(isEditing = true, isLoading = false, draft = task.toDraft()) }
        }
    }

    private fun save() {
        val draft = _uiState.value.draft
        val error = validateCareTaskDraft(draft)
        if (error != null) {
            _uiState.update { it.copy(validationError = error) }
            return
        }
        safeLaunch(onError = { failure ->
            _uiState.update { it.copy(isSaving = false) }
            effectChannel.trySend(CareTaskEditorUiEffect.Failed(failure.localizedMessage))
        }) {
            _uiState.update { it.copy(isSaving = true, validationError = null) }
            try {
                val entity = draft.toEntity(existing, clock.millis())
                if (existing == null) repository.addTask(entity) else repository.updateTask(entity)
                effectChannel.send(CareTaskEditorUiEffect.Saved)
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun toggleArchive() {
        val current = existing ?: return
        safeLaunch(onError = { error ->
            effectChannel.trySend(CareTaskEditorUiEffect.Failed(error.localizedMessage))
        }) {
            repository.setArchived(current.id, !current.isArchived)
            effectChannel.send(CareTaskEditorUiEffect.NavigateBack)
        }
    }

    private fun delete() {
        val current = existing ?: return
        safeLaunch(onError = { error ->
            effectChannel.trySend(CareTaskEditorUiEffect.Failed(error.localizedMessage))
        }) {
            repository.deleteTask(current.id)
            effectChannel.send(CareTaskEditorUiEffect.NavigateBack)
        }
    }

    private fun addTime(raw: String) {
        val normalized = normalizeHhmm(raw) ?: return
        editDraft { draft ->
            if (normalized in draft.reminderTimes) {
                draft
            } else {
                draft.copy(reminderTimes = (draft.reminderTimes + normalized).sorted())
            }
        }
    }

    private fun editDraft(block: (CareTaskDraft) -> CareTaskDraft) {
        _uiState.update { it.copy(draft = block(it.draft), validationError = null) }
    }

    private fun CareTaskDraft.togglePeriod(period: TimePeriod): Set<TimePeriod> =
        if (period in timePeriods) timePeriods - period else timePeriods + period

    private fun CareTaskDraft.toggleWeekday(day: Int): Set<Int> =
        if (day in frequencyDays) frequencyDays - day else frequencyDays + day

    private companion object {
        const val MAX_DURATION_MINUTES = 600
    }
}

/** 编辑已有事项时把实体回填成草稿（字符串列 → 结构化字段）。 */
internal fun CareTask.toDraft(): CareTaskDraft = CareTaskDraft(
    title = title,
    category = category,
    completionMode = completionMode,
    defaultDurationMinutes = defaultDurationMinutes ?: 30,
    scheduleKind = scheduleKind,
    timePeriods = TimePeriods.parse(timePeriods).toSet(),
    reminderTimes = reminderTimes.split(",").map(String::trim).filter(String::isNotEmpty),
    intervalHours = intervalHours,
    frequencyType = frequencyType.lowercase(),
    frequencyInterval = frequencyInterval,
    frequencyDays = frequencyDays.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet(),
    startDate = startDate,
    notes = notes,
)

/**
 * 草稿 → 实体。身份字段（id / careRecipientId / createdAt / isArchived / endDate）来自已有记录；
 * 与所选排期类型无关的字段一律清空，避免切换排期后残留旧值。
 */
internal fun CareTaskDraft.toEntity(existing: CareTask?, nowMs: Long): CareTask = CareTask(
    id = existing?.id ?: 0L,
    careRecipientId = existing?.careRecipientId ?: 0L,
    title = title.trim(),
    category = category,
    completionMode = completionMode,
    defaultDurationMinutes = defaultDurationMinutes.takeIf { completionMode == CareTaskCompletionMode.DURATION },
    scheduleKind = scheduleKind,
    timePeriods = if (scheduleKind == CareTaskScheduleKind.FIXED_TIMES) TimePeriods.encode(timePeriods) else "",
    reminderTimes = if (scheduleKind == CareTaskScheduleKind.FIXED_TIMES) reminderTimes.joinToString(",") else "",
    intervalHours = if (scheduleKind == CareTaskScheduleKind.INTERVAL) intervalHours else 0,
    frequencyType = frequencyType,
    frequencyInterval = frequencyInterval,
    frequencyDays = frequencyDays.sorted().joinToString(","),
    startDate = startDate,
    endDate = existing?.endDate,
    notes = notes.trim(),
    isArchived = existing?.isArchived ?: false,
    createdAt = existing?.createdAt ?: nowMs,
)
