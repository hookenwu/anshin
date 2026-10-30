package com.driezy.medlog.capability.reminders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.driezy.medlog.capability.reminders.application.STARTUP_RECOMPUTE_KEY_PREFIX
import com.driezy.medlog.capability.reminders.application.StartupRecomputeMarker
import com.driezy.medlog.capability.reminders.application.StartupRecomputeRecord
import com.driezy.medlog.capability.reminders.application.decodeStartupRecompute
import com.driezy.medlog.capability.reminders.application.encodeStartupRecompute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 独立的 DataStore 文件：**不放**进 `medlog_settings`，也不进 Room。
 *
 * 这样启动重算的失败标记既不污染用户设置键空间，也不需要新表/新迁移
 * （docs/medication-time-offset.md §3.6 的"不需要新增持久化状态"仍然成立：
 * 这条标记只供观测，收敛始终由"下次启动重跑同一条写路径"完成，没有任何 dirty 标志逻辑读它）。
 */
private val Context.startupRecomputeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "medlog_routine_startup",
)

@Singleton
class PreferencesStartupRecomputeMarker @Inject constructor(@ApplicationContext private val context: Context) :
    StartupRecomputeMarker {

    override val state: Flow<StartupRecomputeRecord?> = context.startupRecomputeDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            preferences.asMap().entries
                .filter { (key, _) -> key.name.startsWith(STARTUP_RECOMPUTE_KEY_PREFIX) }
                .associate { (key, value) -> key.name.removePrefix(STARTUP_RECOMPUTE_KEY_PREFIX) to value.toString() }
                .decodeStartupRecompute()
        }

    override suspend fun latest(): StartupRecomputeRecord? = state.first()

    /**
     * 整条覆盖写：旧键先删干净，避免上一次的 `failure` / `conflicts` 粘到这一次的成功记录上。
     * `edit` 是单文件原子写，写入失败会抛给调用方（启动路径的兜底协程会记日志，不崩 `onCreate`）。
     */
    override suspend fun write(record: StartupRecomputeRecord) {
        val encoded = record.encodeStartupRecompute()
        context.startupRecomputeDataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(STARTUP_RECOMPUTE_KEY_PREFIX) }
                .forEach { stale -> preferences.remove(stale) }
            encoded.forEach { (key, value) ->
                preferences[stringPreferencesKey(STARTUP_RECOMPUTE_KEY_PREFIX + key)] = value
            }
        }
    }
}
