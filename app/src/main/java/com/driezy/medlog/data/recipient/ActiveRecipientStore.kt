package com.driezy.medlog.data.recipient

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.driezy.medlog.data.local.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前选中的家庭成员 id（[NO_RECIPIENT] = 尚未选择成员）。
 *
 * 阶段 0 只持久化"当前成员"这一项；作息/时区等设置本身仍是设备级的，
 * 阶段 1 再把它们按成员拆分。
 */
@Singleton
class ActiveRecipientStore @Inject constructor(@ApplicationContext private val context: Context) {
    val recipientId: Flow<Long> = context.settingsDataStore.data
        .map { it[KEY] ?: NO_RECIPIENT }
        .distinctUntilChanged()

    suspend fun current(): Long = recipientId.first()

    suspend fun set(id: Long) {
        context.settingsDataStore.edit { it[KEY] = id }
    }

    companion object {
        /** 尚未选择成员：所有按成员收口的数据访问都应返回空/拒绝写入。 */
        const val NO_RECIPIENT = 0L

        private val KEY = longPreferencesKey("active_recipient_id")
    }
}
