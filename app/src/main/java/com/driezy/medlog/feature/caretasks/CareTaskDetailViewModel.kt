package com.driezy.medlog.feature.caretasks

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.feature.caretasks.application.CareTaskCompletionUseCase
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * 照护事项详情页 ViewModel。
 *
 * 读路径直接集合仓库的成员作用域日志流；写路径（完成/开始/跳过/撤销）**全部**经
 * [CareTaskCompletionUseCase]，与今日页/通知保持同一命令入口。
 * 今日排期由 `toDomainSchedule()` 复用领域层展开，不另起发生器。
 */
@HiltViewModel
class CareTaskDetailViewModel @Inject constructor(
    private val repository: CareTaskRepository,
    private val completion: CareTaskCompletionUseCase,
    private val preferences: UserPreferencesRepository,
    private val clock: Clock,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(CareTaskDetailUiState())
    val uiState = _uiState.asStateFlow()

    private val effectChannel = Channel<CareTaskDetailUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val time = MutableStateFlow(clock.instant())
    private var task: CareTask? = null
    private var observation: Job? = null

    fun onAction(action: CareTaskDetailUiAction) {
        when (action) {
            is CareTaskDetailUiAction.Load -> load(action.taskId)
            is CareTaskDetailUiAction.Complete -> command { complete(action.scheduledTimeMs) }
            is CareTaskDetailUiAction.Start -> command { start(action.scheduledTimeMs) }
            is CareTaskDetailUiAction.Skip -> command { skip(action.scheduledTimeMs) }
            is CareTaskDetailUiAction.Undo -> command { undo(action.scheduledTimeMs) }
            CareTaskDetailUiAction.Archive -> archive()
            CareTaskDetailUiAction.Delete -> delete()
            CareTaskDetailUiAction.RefreshTime -> time.value = clock.instant()
        }
    }

    private fun load(taskId: Long) {
        observation?.cancel()
        observation = viewModelScope.launch {
            val loaded = repository.getTaskById(taskId)
            if (loaded == null) {
                _uiState.update { it.copy(isLoading = false, failed = true) }
                return@launch
            }
            task = loaded
            combine(repository.getLogsForTask(taskId), time, preferences.settingsFlow) { logs, now, prefs ->
                Triple(logs, now, prefs.reminderZone(clock.zone))
            }
                .catch { error ->
                    _uiState.update { it.copy(isLoading = false, failed = true) }
                    effectChannel.send(CareTaskDetailUiEffect.Failed(error.localizedMessage))
                }
                .collect { (logs, now, zone) ->
                    _uiState.value = present(loaded, logs, now.toEpochMilli(), zone)
                }
        }
    }

    private suspend fun complete(scheduledTimeMs: Long) {
        val id = task?.id ?: return
        completion.complete(id, scheduledTimeMs)
        time.value = clock.instant()
    }

    private suspend fun start(scheduledTimeMs: Long) {
        val id = task?.id ?: return
        completion.start(id, scheduledTimeMs)
        time.value = clock.instant()
    }

    private suspend fun skip(scheduledTimeMs: Long) {
        val id = task?.id ?: return
        completion.skip(id, scheduledTimeMs)
        time.value = clock.instant()
    }

    private suspend fun undo(scheduledTimeMs: Long) {
        val id = task?.id ?: return
        completion.undo(id, scheduledTimeMs)
        time.value = clock.instant()
    }

    private fun command(block: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        safeLaunch(onError = { error ->
            _uiState.update { it.copy(isSaving = false) }
            effectChannel.trySend(CareTaskDetailUiEffect.Failed(error.localizedMessage))
        }) {
            try {
                block()
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun archive() {
        val current = task ?: return
        command {
            repository.setArchived(current.id, !current.isArchived)
            effectChannel.send(CareTaskDetailUiEffect.NavigateBack)
        }
    }

    private fun delete() {
        val current = task ?: return
        command {
            repository.deleteTask(current.id)
            effectChannel.send(CareTaskDetailUiEffect.NavigateBack)
        }
    }

    private fun present(task: CareTask, logs: List<CareTaskLog>, nowMs: Long, zone: ZoneId): CareTaskDetailUiState {
        val scheduledTimes = if (task.scheduleKind == CareTaskScheduleKind.AS_NEEDED) {
            listOf(nowMs)
        } else {
            task.todayOccurrences(zone, nowMs).map { it.scheduledAt.toEpochMilli() }
        }
        val occurrences = scheduledTimes.map { scheduledMs ->
            val log = logs.firstOrNull { it.scheduledTimeMs == scheduledMs }
            CareTaskOccurrenceUi(
                scheduledTimeMs = scheduledMs,
                timeLabel = if (task.scheduleKind == CareTaskScheduleKind.AS_NEEDED) {
                    ""
                } else {
                    Instant.ofEpochMilli(scheduledMs).atZone(zone).toLocalTime().format(HHMM_FORMAT)
                },
                status = log?.status,
                elapsedMinutes = elapsedMinutes(log, nowMs),
            )
        }
        return CareTaskDetailUiState(
            task = task,
            occurrences = occurrences,
            recentLogs = logs.sortedByDescending { it.scheduledTimeMs }.take(RECENT_LOG_LIMIT),
            isLoading = false,
        )
    }

    private fun elapsedMinutes(log: CareTaskLog?, nowMs: Long): Int? {
        if (log?.status != CareTaskLogStatus.IN_PROGRESS) return null
        val start = log.actualStartMs ?: return null
        return ((nowMs - start) / 60_000L).toInt().coerceAtLeast(0)
    }

    private companion object {
        const val RECENT_LOG_LIMIT = 20
    }
}
