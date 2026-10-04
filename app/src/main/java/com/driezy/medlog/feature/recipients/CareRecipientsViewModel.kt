package com.driezy.medlog.feature.recipients

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.repository.CareRecipientRepository
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
 * 只注入 [CareRecipientRepository]：门禁页与成员管理页都复用同一个状态源，
 * 保证两处看到的成员列表 / 当前成员完全一致。
 */
@HiltViewModel
class CareRecipientsViewModel @Inject constructor(private val repository: CareRecipientRepository) : BaseViewModel() {

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
            is CareRecipientsUiAction.SetActive -> safeLaunch { repository.setActiveRecipient(action.id) }
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
