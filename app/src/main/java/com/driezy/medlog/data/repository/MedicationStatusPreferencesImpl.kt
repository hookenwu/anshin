package com.driezy.medlog.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.driezy.medlog.data.local.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MedicationStatusPreferences] 的 DataStore 实现（对齐 `care_event_nudged_day#…` 的做法，§8.1）。
 *
 * 用既有共享 DataStore（`medlog_settings`），键按成员分片，无遗留全局键、无成员回退。
 */
@Singleton
class MedicationStatusPreferencesImpl @Inject constructor(@param:ApplicationContext private val context: Context) :
    MedicationStatusPreferences {

    private fun autoReconciledKey(recipientId: Long): Preferences.Key<Long> =
        longPreferencesKey("medication_status_auto_reconciled_at#$recipientId")

    private fun userCheckedKey(recipientId: Long): Preferences.Key<Long> =
        longPreferencesKey("medication_status_user_checked_date#$recipientId")

    override suspend fun autoReconciledAtMs(recipientId: Long): Long? =
        context.settingsDataStore.data.first()[autoReconciledKey(recipientId)]

    override suspend fun setAutoReconciledAtMs(recipientId: Long, atMs: Long) {
        context.settingsDataStore.edit { it[autoReconciledKey(recipientId)] = atMs }
    }

    override suspend fun userCheckedDate(recipientId: Long): Long? =
        context.settingsDataStore.data.first()[userCheckedKey(recipientId)]

    override fun userCheckedDateFlow(recipientId: Long): Flow<Long?> =
        context.settingsDataStore.data.map { it[userCheckedKey(recipientId)] }

    override suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long) {
        context.settingsDataStore.edit { it[userCheckedKey(recipientId)] = epochDay }
    }

    override suspend fun clearForRecipient(recipientId: Long) {
        context.settingsDataStore.edit { prefs ->
            val keys = listOf(
                autoReconciledKey(recipientId),
                userCheckedKey(recipientId),
            )
            keys.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                prefs.remove(key as Preferences.Key<Any>)
            }
        }
    }
}
