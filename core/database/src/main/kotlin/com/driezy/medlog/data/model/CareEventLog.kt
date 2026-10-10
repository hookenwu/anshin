package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件类型 = **代码常量**，不是用户可管理的数据（docs/tracked-events-spec.md §4 D1）。
 *
 * 首期只有 [BOWEL]；将来加排尿/呕吐只是新增一个常量 + 一组成员级偏好键，**无 schema 变更、无类型表、无类型 CRUD UI**。
 */
object CareEventKind {
    const val BOWEL = "BOWEL"

    /** 首期全部 kind（仅排便）。 */
    val all = listOf(BOWEL)
}

/**
 * 照护事件日志（首期仅排便）——「发生了才记录的事件」（docs/tracked-events-spec.md §2、§4）。
 *
 * 与 `CareTask`（计划型干预）、`HealthRecord`（数值型体征）都不同：这是**回溯记录 + 间隔追踪**。
 * 一条日志带两个时间戳（D2）：
 * - [occurredAtMs] 事件**实际发生**时刻（补记可为过去）；**间隔一律按它计算**。
 * - [createdAtMs] 照护者**录入**时刻（补记时 = 录入时刻，与发生时间独立）。
 *
 * 成员隔离沿用既有约定（NOT NULL + FK CASCADE，见 `CareTodo`）。**就地编辑 + 物理删除，无版本审计**（D4）。
 */
@Entity(
    tableName = "care_event_logs",
    foreignKeys = [
        ForeignKey(
            entity = CareRecipient::class,
            parentColumns = ["id"],
            childColumns = ["careRecipientId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // 复合索引同时覆盖仓储主查询「取某成员某 kind 的最新一条」。
        Index(value = ["careRecipientId", "kind", "occurredAtMs"]),
    ],
)
data class CareEventLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 成员隔离，与既有实体同约定（NOT NULL + FK CASCADE）。 */
    val careRecipientId: Long = 0,
    /** 事件类型，取 [CareEventKind.*]；首期恒为 [CareEventKind.BOWEL]。 */
    val kind: String = CareEventKind.BOWEL,
    /** 事件实际发生时间（补记可为过去；间隔按此算）。 */
    val occurredAtMs: Long,
    /** 可选备注。 */
    val note: String? = null,
    /** 录入时刻（发生 vs 记录，见 D2）。 */
    val createdAtMs: Long = System.currentTimeMillis(),
    /** 就地编辑时置位；无版本链、无审计（D4）。 */
    val updatedAtMs: Long? = null,
)
