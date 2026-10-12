package com.driezy.medlog.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * 药品状态「两种事实分离」的成员级偏好（docs/medication-status-spec.md §8 D9）。
 *
 * - [autoReconciledAtMs] = **系统自动核对**写入的事实（`lastAutoReconciledAtMs`）；
 * - [userCheckedDate] = **用户主动核对**写入的事实（`lastUserCheckedDate`，本地 `epochDay`）。
 *
 * 两个键按成员分片；自动通道**绝不**写 [setUserCheckedDate]（§8.3）。
 */
interface MedicationStatusPreferences {
    suspend fun autoReconciledAtMs(recipientId: Long): Long?
    suspend fun setAutoReconciledAtMs(recipientId: Long, atMs: Long)
    suspend fun userCheckedDate(recipientId: Long): Long?

    /**
     * 观察成员级 `lastUserCheckedDate` 的**变化流**（供「今天尚未核对」提示响应无事件确认）。
     *
     * 纯无变更的核对**零状态事件**，只写这一 DataStore 标记；只观察状态事件的读者会漏掉它
     * （docs/medication-daily-reconcile.md T6 / 裁决 1），故提示须额外观察本流。
     */
    fun userCheckedDateFlow(recipientId: Long): Flow<Long?>
    suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long)
    suspend fun clearForRecipient(recipientId: Long)
}
