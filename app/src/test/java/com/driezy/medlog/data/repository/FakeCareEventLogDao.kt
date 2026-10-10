package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareEventLogDao
import com.driezy.medlog.data.model.CareEventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 照护事件 DAO 的内存假件：成员作用域不在这里过滤（由仓库负责），只按 recipientId/kind
 * 做与真 DAO 同构的查询，便于在 JVM 上验证仓库的成员收口与写入规则。
 */
class FakeCareEventLogDao : CareEventLogDao {

    private val rows = MutableStateFlow<List<CareEventLog>>(emptyList())
    private var nextId = 1L

    fun seed(log: CareEventLog): Long {
        val id = if (log.id != 0L) log.id else nextId++
        rows.value = rows.value + log.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    fun stored(): List<CareEventLog> = rows.value

    fun storedById(id: Long): CareEventLog? = rows.value.firstOrNull { it.id == id }

    private fun matching(recipientId: Long, kind: String): List<CareEventLog> =
        rows.value.filter { it.careRecipientId == recipientId && it.kind == kind }
            .sortedWith(compareByDescending<CareEventLog> { it.occurredAtMs }.thenByDescending { it.id })

    override fun getLogs(recipientId: Long, kind: String): Flow<List<CareEventLog>> =
        rows.map { matching(recipientId, kind) }

    override fun getNewest(recipientId: Long, kind: String): Flow<CareEventLog?> =
        rows.map { matching(recipientId, kind).firstOrNull() }

    override suspend fun getLogsOnce(recipientId: Long, kind: String): List<CareEventLog> = matching(recipientId, kind)

    override suspend fun getNewestOnce(recipientId: Long, kind: String): CareEventLog? =
        matching(recipientId, kind).firstOrNull()

    override suspend fun getById(id: Long): CareEventLog? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(log: CareEventLog): Long {
        val id = nextId++
        rows.value = rows.value + log.copy(id = id)
        return id
    }

    override suspend fun update(log: CareEventLog) {
        rows.value = rows.value.map { if (it.id == log.id) log else it }
    }

    override suspend fun delete(log: CareEventLog) {
        rows.value = rows.value.filterNot { it.id == log.id }
    }
}
