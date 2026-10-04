package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareRecipient
import kotlinx.coroutines.flow.Flow

/**
 * 家庭成员档案（一级实体）的唯一真实来源。
 *
 * 阶段 0 只做本地档案与"当前成员"选择；不做账号/云同步，
 * 但 `uuid` 已稳定保存，供将来同步对齐。
 */
interface CareRecipientRepository {
    fun observeRecipients(): Flow<List<CareRecipient>>

    suspend fun getRecipients(): List<CareRecipient>

    suspend fun getById(id: Long): CareRecipient?

    /** 创建成员；若当前没有任何成员，则自动选中新成员。 */
    suspend fun create(displayName: String): Long

    suspend fun rename(id: Long, displayName: String): Boolean

    /** 删除成员及其全部数据（DB 级联）；若删除的是当前成员则自动切换到剩余成员或"未选择"。 */
    suspend fun delete(id: Long)

    fun observeActiveRecipientId(): Flow<Long>

    suspend fun activeRecipient(): CareRecipient?

    suspend fun setActiveRecipient(id: Long)
}
