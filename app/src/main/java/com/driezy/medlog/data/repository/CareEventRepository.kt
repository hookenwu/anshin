package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.model.CareEventLog
import kotlinx.coroutines.flow.Flow

/**
 * 照护事件（首期排便）SSOT 仓库（docs/tracked-events-spec.md §4）。
 *
 * 与 [CareTodoRepository] 同一套成员维度约定：查询按「当前家庭成员」过滤，写入前绑定当前成员；
 * 未选择成员时读返回空（`NO_RECIPIENT`）、写直接报错。间隔是**派生值**（不落库），
 * 写入收敛到本仓库的单一命令入口；写入**只更新派生状态，绝不弹通知**（R11）。
 */
interface CareEventRepository {

    /** 某成员某 kind 的全部日志，最新发生在前。 */
    fun getLogs(kind: String = CareEventKind.BOWEL): Flow<List<CareEventLog>>

    /** 某成员某 kind 的最新一条（间隔锚点）；无记录时不发射或发射 null。 */
    fun getNewest(kind: String = CareEventKind.BOWEL): Flow<CareEventLog?>

    /** 一次性读取全部日志（供契约/回归断言）。 */
    suspend fun getLogsOnce(kind: String = CareEventKind.BOWEL): List<CareEventLog>

    /** 一次性读取最新一条（锚点）。 */
    suspend fun getNewestOnce(kind: String = CareEventKind.BOWEL): CareEventLog?

    /**
     * 记录一次事件（D2）。[occurredAtMs] 为 null 时取「现在」（occurredAtMs == createdAtMs）；
     * 补记传入过去的时刻。[createdAtMs] 恒为录入时刻，由仓库盖章。
     */
    suspend fun record(occurredAtMs: Long? = null, note: String? = null, kind: String = CareEventKind.BOWEL): Long

    /** 就地编辑发生时刻/备注（D4）：置 `updatedAtMs`，无版本链、无审计。 */
    suspend fun edit(id: Long, occurredAtMs: Long, note: String? = null)

    /** 物理删除（对齐 `MedicationLog` 撤销＝删除）。 */
    suspend fun delete(id: Long)
}
