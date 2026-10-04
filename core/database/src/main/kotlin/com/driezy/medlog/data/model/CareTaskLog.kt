package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照护事项的执行记录。
 *
 * 通过 `careTaskId` 继承所属成员（不冗余 careRecipientId，与 medication_logs 一致）；
 * 唯一键 `(careTaskId, scheduledTimeMs)` 复用用药的防重方式。
 * 撤销 = 物理删除（与用药一致）。
 *
 * 过程数据归属（用户决策）：**体位等"本次结果"的分类值归本日志**（[postureNote]）；
 * 数值型过程数据（血氧、氧流量、读数次数）走健康模块的 HealthRecord，本表不存。
 */
@Entity(
    tableName = "care_task_logs",
    foreignKeys = [
        ForeignKey(
            entity = CareTask::class,
            parentColumns = ["id"],
            childColumns = ["careTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("careTaskId"),
        Index("scheduledTimeMs"),
        Index("revisionType"),
        Index(value = ["careTaskId", "scheduledTimeMs"], unique = true),
    ],
)
data class CareTaskLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val careTaskId: Long,
    /** 计划时间戳（ms）。 */
    val scheduledTimeMs: Long,
    val status: CareTaskLogStatus = CareTaskLogStatus.DONE,
    /** 时长型的开始时间；打卡型为 null。 */
    val actualStartMs: Long? = null,
    /** 完成时间（打卡型的唯一时间点）。 */
    val actualEndMs: Long? = null,
    /** 冗余便于统计；由起止算出。 */
    val actualDurationMinutes: Int? = null,
    /** 本次体位（如"左侧/右侧/平卧"）等分类过程数据；数值型数据走健康模块。 */
    val postureNote: String? = null,
    val notes: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long? = null,
    val revisionType: LogRevisionType = LogRevisionType.ORIGINAL,
)

enum class CareTaskLogStatus { DONE, SKIPPED, IN_PROGRESS }
