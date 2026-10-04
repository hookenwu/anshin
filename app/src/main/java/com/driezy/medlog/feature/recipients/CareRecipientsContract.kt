package com.driezy.medlog.feature.recipients

import com.driezy.medlog.data.model.CareRecipient

/**
 * 家庭成员（care recipient）UI 状态。
 *
 * [activeRecipientId] 为 0 表示"尚未选择当前成员"；门禁会在成员存在但未选中时
 * 自动把第一位设为当前成员（见 [CareRecipientsViewModel]）。
 */
data class CareRecipientsUiState(
    val isLoading: Boolean = true,
    val recipients: List<CareRecipient> = emptyList(),
    val activeRecipientId: Long = 0L,
    val isSaving: Boolean = false,
)

sealed interface CareRecipientsUiAction {
    data class Create(val displayName: String) : CareRecipientsUiAction

    data class Rename(val id: Long, val displayName: String) : CareRecipientsUiAction

    data class Delete(val id: Long) : CareRecipientsUiAction

    data class SetActive(val id: Long) : CareRecipientsUiAction
}

sealed interface CareRecipientsUiEffect {
    data object RenameFailed : CareRecipientsUiEffect
}
