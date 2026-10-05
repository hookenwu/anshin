package com.driezy.medlog.feature.caretasks

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * 照护事项列表页 ViewModel。
 *
 * 活跃/已归档两组直接 collect 仓库的成员作用域流，因此切换当前成员无需重启即反射；
 * 今日状态另取一次当日日志（任务流每次重发时刷新），只用于固定时刻型的「当前时间槽」。
 */
@HiltViewModel
class CareTasksViewModel @Inject constructor(
    private val repository: CareTaskRepository,
    private val preferences: UserPreferencesRepository,
    private val clock: Clock,
) : BaseViewModel() {

    private val showArchived = MutableStateFlow(false)
    private val tick = MutableStateFlow(0)
    private val todayLogs = MutableStateFlow<List<CareTaskLog>>(emptyList())

    private val effectChannel = Channel<CareTasksUiEffect>(Channel.BUFFERED)
    val uiEffect = effectChannel.receiveAsFlow()

    val uiState = combine(
        repository.getActiveTasks(),
        repository.getArchivedTasks(),
        showArchived,
        todayLogs,
        preferences.settingsFlow,
    ) { active, archived, show, logs, prefs ->
        val nowMs = clock.millis()
        val zone = prefs.reminderZone(clock.zone)
        CareTasksUiState(
            activeTasks = active,
            archivedTasks = archived,
            showArchived = show,
            todayStatus = active.mapNotNull { task ->
                task.todayStatus(zone, nowMs, logs)?.let { task.id to it }
            }.toMap(),
            isLoading = false,
        )
    }
        .catch { error ->
            effectChannel.send(CareTasksUiEffect.Failed(error.localizedMessage))
            emit(CareTasksUiState(isLoading = false, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareTasksUiState())

    init {
        // 任务流本身按成员作用域；成员切换或任务变动时刷新一次当日日志。
        safeLaunch {
            combine(repository.getActiveTasks(), tick) { tasks, _ -> tasks }.collect { refreshLogs() }
        }
    }

    fun onAction(action: CareTasksUiAction) {
        when (action) {
            is CareTasksUiAction.SetShowArchived -> showArchived.value = action.show
            CareTasksUiAction.Refresh -> tick.update { it + 1 }
        }
    }

    private suspend fun refreshLogs() {
        val zone = runCatching { preferences.settingsFlow.first().reminderZone(clock.zone) }
            .getOrDefault(clock.zone)
        todayLogs.value = runCatching { repository.getLogsForToday(todayStartMs(zone)) }
            .getOrDefault(emptyList())
    }

    private fun todayStartMs(zone: ZoneId): Long = clock.instant()
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}
