package com.driezy.medlog.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** [MedicationStatusPreferences] 的纯内存假实现（JVM 单测用）。 */
class FakeMedicationStatusPreferences : MedicationStatusPreferences {
    private val autoReconciled = mutableMapOf<Long, Long>()
    private val userChecked = mutableMapOf<Long, Long>()

    /** 每次写入（含清除）自增，供 [userCheckedDateFlow] 派发变化（对齐真实 DataStore 的发射）。 */
    private val revision = MutableStateFlow(0L)

    override suspend fun autoReconciledAtMs(recipientId: Long): Long? = autoReconciled[recipientId]

    override suspend fun setAutoReconciledAtMs(recipientId: Long, atMs: Long) {
        autoReconciled[recipientId] = atMs
    }

    override suspend fun userCheckedDate(recipientId: Long): Long? = userChecked[recipientId]

    override fun userCheckedDateFlow(recipientId: Long): Flow<Long?> = revision.map { userChecked[recipientId] }

    override suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long) {
        userChecked[recipientId] = epochDay
        revision.update { it + 1 }
    }

    override suspend fun clearForRecipient(recipientId: Long) {
        autoReconciled.remove(recipientId)
        userChecked.remove(recipientId)
        revision.update { it + 1 }
    }
}
