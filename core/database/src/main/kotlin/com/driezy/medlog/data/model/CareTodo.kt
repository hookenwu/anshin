package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待办事项（一次性跟进项，docs/todos.md §2.1）。
 *
 * 与 Medication / CareTask 平级的一等领域实体，挂在同一 CareRecipient 下；但**刻意**不并入二者：
 * 无排期、不重复、无剂量库存、无时长，只表达「观察 → 建议动作 → 闭环」的跟进状态。
 *
 * 刻意不加 `isArchived` / `linkedCareTaskId` / `priority` / `assignee`（docs/todos.md §2.1）：
 * 终态（DONE / CANCELLED）本身即「已关闭」，再引入归档会产生两套关闭语义。
 */
@Entity(
    tableName = "care_todos",
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
        Index("status"),
    ],
)
data class CareTodo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 成员隔离，与既有实体同约定（NOT NULL + FK CASCADE）。 */
    val careRecipientId: Long,
    /** 待办正文，如「让护士看一下压疮风险」。 */
    val title: String,
    /** 状态机：OPEN / DONE / CANCELLED，见 [CareTodoStatus]。 */
    val status: String = CareTodoStatus.OPEN,
    /** 可选截止时刻；逾期是派生量（OPEN && dueAtMs != null && dueAtMs < now），不落库。 */
    val dueAtMs: Long? = null,
    /** 预留来源类型（OBSERVATION / SYMPTOM / …）；MVP 只存不解读。 */
    val sourceType: String? = null,
    /** 预留来源记录 id；不是外键、不做级联。 */
    val sourceId: Long? = null,
    /** 来源备注/原文摘要，如「护工：骶尾处发红」。 */
    val sourceNote: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    /** DONE / CANCELLED 时写入；撤销回 OPEN 时必须显式清空。 */
    val closedAtMs: Long? = null,
    /** 可选：怎么处理的 / 为什么取消。 */
    val resolutionNote: String? = null,
)

/**
 * 待办三态状态机（docs/todos.md §2.2）——语义互不重叠，**无 CLOSED、无落库的 OVERDUE**。
 *
 * - [OPEN]：未闭环，需处理；
 * - [DONE]：动作真的做了（将来算跟进完成率时计入完成）；
 * - [CANCELLED]：决定不做 / 不需要了 / 误报（既不算完成也不算逾期）。
 */
object CareTodoStatus {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"

    /** 终态：从首页消失、只在历史里出现。 */
    val closed = listOf(DONE, CANCELLED)

    val all = listOf(OPEN, DONE, CANCELLED)
}
