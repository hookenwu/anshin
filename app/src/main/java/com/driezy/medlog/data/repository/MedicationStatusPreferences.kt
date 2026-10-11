package com.driezy.medlog.data.repository

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
    suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long)
    suspend fun clearForRecipient(recipientId: Long)
}
