package com.driezy.medlog.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.driezy.medlog.data.local.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照护事件提醒偏好实现（docs/tracked-events-spec.md §4 D3 / §5）。
 *
 * 用既有共享 DataStore（`medlog_settings`），键**按 (成员, kind) 分片**，无遗留全局键、
 * 无成员回退——语义就是「照护者对自己关注节奏的设置」。缺省：开关 false、阈值 3、日标记缺省。
 */
@Singleton
class CareEventReminderPreferencesImpl @Inject constructor(@param:ApplicationContext private val context: Context) :
    CareEventReminderPreferences {

    private fun enabledKey(recipientId: Long, kind: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("care_event_reminder_enabled#$recipientId#$kind")

    private fun thresholdKey(recipientId: Long, kind: String): Preferences.Key<Int> =
        intPreferencesKey("care_event_reminder_threshold_days#$recipientId#$kind")

    private fun nudgedKey(recipientId: Long, kind: String): Preferences.Key<Long> =
        longPreferencesKey("care_event_nudged_day#$recipientId#$kind")

    override suspend fun isEnabled(recipientId: Long, kind: String): Boolean =
        context.settingsDataStore.data.first()[enabledKey(recipientId, kind)] ?: false

    override suspend fun setEnabled(recipientId: Long, kind: String, enabled: Boolean) {
        context.settingsDataStore.edit { it[enabledKey(recipientId, kind)] = enabled }
    }

    override suspend fun thresholdDays(recipientId: Long, kind: String): Int =
        context.settingsDataStore.data.first()[thresholdKey(recipientId, kind)]
            ?: CareEventReminderPreferencesDefaults.THRESHOLD_DAYS

    override suspend fun setThresholdDays(recipientId: Long, kind: String, days: Int) {
        context.settingsDataStore.edit { it[thresholdKey(recipientId, kind)] = days }
    }

    override suspend fun nudgedDay(recipientId: Long, kind: String): Long? =
        context.settingsDataStore.data.first()[nudgedKey(recipientId, kind)]

    override suspend fun setNudgedDay(recipientId: Long, kind: String, epochDay: Long) {
        context.settingsDataStore.edit { it[nudgedKey(recipientId, kind)] = epochDay }
    }

    override suspend fun clearForRecipient(recipientId: Long) {
        context.settingsDataStore.edit { prefs ->
            val marker = "#$recipientId#"
            val toRemove = prefs.asMap().keys
                .filter { it.name.startsWith(CARE_EVENT_PREFIX) && it.name.contains(marker) }
            toRemove.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                prefs.remove(key as Preferences.Key<Any>)
            }
        }
    }

    override fun reminderSetting(recipientId: Long, kind: String): Flow<CareEventReminderSetting> =
        context.settingsDataStore.data.map { prefs ->
            CareEventReminderSetting(
                enabled = prefs[enabledKey(recipientId, kind)] ?: false,
                thresholdDays = prefs[thresholdKey(recipientId, kind)]
                    ?: CareEventReminderPreferencesDefaults.THRESHOLD_DAYS,
            )
        }

    private companion object {
        const val CARE_EVENT_PREFIX = "care_event_"
    }
}

/** 照护事件提醒的缺省值（与 spec D3/D5 一致）。 */
object CareEventReminderPreferencesDefaults {
    const val THRESHOLD_DAYS = 3
}
