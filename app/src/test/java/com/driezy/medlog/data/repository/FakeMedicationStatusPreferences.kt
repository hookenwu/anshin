package com.driezy.medlog.data.repository

/** [MedicationStatusPreferences] 的纯内存假实现（JVM 单测用）。 */
class FakeMedicationStatusPreferences : MedicationStatusPreferences {
    private val autoReconciled = mutableMapOf<Long, Long>()
    private val userChecked = mutableMapOf<Long, Long>()

    override suspend fun autoReconciledAtMs(recipientId: Long): Long? = autoReconciled[recipientId]

    override suspend fun setAutoReconciledAtMs(recipientId: Long, atMs: Long) {
        autoReconciled[recipientId] = atMs
    }

    override suspend fun userCheckedDate(recipientId: Long): Long? = userChecked[recipientId]

    override suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long) {
        userChecked[recipientId] = epochDay
    }

    override suspend fun clearForRecipient(recipientId: Long) {
        autoReconciled.remove(recipientId)
        userChecked.remove(recipientId)
    }
}
