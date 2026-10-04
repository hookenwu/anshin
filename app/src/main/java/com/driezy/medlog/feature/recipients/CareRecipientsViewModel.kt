package com.driezy.medlog.feature.recipients

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.capability.widgets.WidgetRefresher
import com.driezy.medlog.data.repository.CareRecipientRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 家庭成员门禁与管理的共享 ViewModel（阶段 0）。
 *
 * 门禁页、成员管理页与一级导航切换器都复用同一个状态源，
 * 保证各处看到的成员列表 / 当前成员完全一致；切换成员时在 [setActive]
 * 中额外触发小组件刷新与提醒重算。
 */
@HiltViewModel
class CareRecipientsViewModel @Inject constructor(
    private val repository: CareRecipientRepository,
    private val widgetRefresher: WidgetRefresher,
    private val reconcileReminders: ReconcileRemindersUseCase,
) : BaseViewModel() {

    private val saving = MutableStateFlow(false)

    private val effectChannel = Channel<CareRecipientsUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    val uiState = combine(
        repository.observeRecipients(),
        repository.observeActiveRecipientId(),
        saving,
    ) { recipients, activeId, isSaving ->
        CareRecipientsUiState(
            isLoading = false,
            recipients = recipients,
            activeRecipientId = activeId,
            isSaving = isSaving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareRecipientsUiState())

    init {
        // 需求 3：存在成员但尚未选中时，自动把第一位成员设为当前成员。
        safeLaunch {
            combine(
                repository.observeRecipients(),
                repository.observeActiveRecipientId(),
            ) { recipients, activeId -> recipients to activeId }
                .collect { (recipients, activeId) ->
                    if (recipients.isNotEmpty() && activeId == 0L) {
                        repository.setActiveRecipient(recipients.first().id)
                    }
                }
        }
    }

    fun onAction(action: CareRecipientsUiAction) {
        when (action) {
            is CareRecipientsUiAction.Create -> create(action.displayName)
            is CareRecipientsUiAction.Rename -> rename(action.id, action.displayName)
            is CareRecipientsUiAction.Delete -> safeLaunch { repository.delete(action.id) }
            is CareRecipientsUiAction.SetActive -> setActive(action.id)
        }
    }

    /**
     * 切换当前成员：持久化后立即刷新桌面小组件与提醒投影，
     * 使闹钟与小组件立刻跟随新成员。副作用放在 ViewModel 层（非 repository），
     * 保持数据层不依赖 capability。
     */
    private fun setActive(id: Long) {
        safeLaunch {
            repository.setActiveRecipient(id)
            widgetRefresher.refreshAll()
            reconcileReminders.all(ReminderReconcileReason.MEDICATION_CHANGED)
        }
    }

    private fun create(displayName: String) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        safeLaunch {
            saving.value = true
            try {
                repository.create(name)
            } finally {
                saving.value = false
            }
        }
    }

    private fun rename(id: Long, displayName: String) {
        val name = displayName.trim()
        if (name.isEmpty()) {
            safeLaunch { effectChannel.send(CareRecipientsUiEffect.RenameFailed) }
            return
        }
        safeLaunch {
            saving.value = true
            val renamed = try {
                repository.rename(id, name)
            } finally {
                saving.value = false
            }
            if (!renamed) {
                effectChannel.send(CareRecipientsUiEffect.RenameFailed)
            }
        }
    }
}
