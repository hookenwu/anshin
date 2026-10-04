package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照护事项（非药物干预：吸氧、翻身、言语训练…）。
 *
 * 与 Medication 平级的一等领域实体，挂在同一 CareRecipient 下，复用药物的排期/提醒/
 * 记录底座，但剔除用药专有概念（剂量、单位、库存、相互作用输入、PRN 日最大量）。
 * 「作息」在这里只是被复用的时间坐标系，不寄居作息页面。
 */
@Entity(
    tableName = "care_tasks",
    foreignKeys = [
        ForeignKey(
            entity = CareRecipient::class,
            parentColumns = ["id"],
            childColumns = ["careRecipientId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("careRecipientId"),
        Index("isArchived"),
    ],
)
data class CareTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val careRecipientId: Long,
    /** 事项名称（吸氧、翻身、读数练习）。 */
    val title: String,
    /** 分类标签，见 [CareTaskCategory]，用于今日页分组/筛选。 */
    val category: String = CareTaskCategory.OTHER,
    /** 完成语义：一键打卡 / 需要时长。 */
    val completionMode: CareTaskCompletionMode = CareTaskCompletionMode.TOGGLE,
    /** 时长型的默认时长（分钟）；打卡型为 null。 */
    val defaultDurationMinutes: Int? = null,
    /** 排期类型：固定时刻 / 完成后计时 / 按需。 */
    val scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
    /** 作息时段 key 列表（TimePeriods 编码，逗号分隔）；FIXED_TIMES 用。 */
    val timePeriods: String = "",
    /** 逗号分隔 HH:mm；FIXED_TIMES 用。 */
    val reminderTimes: String = "",
    /** 完成后计时的小时间隔（如翻身 2 小时）；INTERVAL 用。 */
    val intervalHours: Int = 0,
    /** 复用 Medication 的频率语义：DAILY / INTERVAL / SPECIFIC_DAYS。 */
    val frequencyType: String = "DAILY",
    val frequencyInterval: Int = 1,
    val frequencyDays: String = "",
    val startDate: Long = System.currentTimeMillis(),
    val endDate: Long? = null,
    val notes: String = "",
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 完成语义（决策 2：本期只做打卡与时长两种）。 */
enum class CareTaskCompletionMode { TOGGLE, DURATION }

/** 排期类型（决策 3：固定时刻与完成后计时可混用）。 */
enum class CareTaskScheduleKind { FIXED_TIMES, INTERVAL, AS_NEEDED }

/** 照护事项分类标签。 */
object CareTaskCategory {
    const val RESPIRATORY = "呼吸治疗"
    const val MOBILITY = "体位与活动"
    const val SPEECH = "言语训练"
    const val OTHER = "其他"

    val all = listOf(RESPIRATORY, MOBILITY, SPEECH, OTHER)
}
