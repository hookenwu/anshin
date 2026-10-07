package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照护笔记的多态关联（docs/care-notes.md §4）。只表达「笔记具体与什么对象相关」，
 * 「这条笔记属于谁」由 [CareNote.careRecipientId] 决定——因此**不存在 `MEMBER` 目标类型**。
 *
 * [targetId] **刻意不建外键、不级联**：沿用仓库里 `CareTodo.sourceType/sourceId` 的既有约定
 * （多态关联一律不建外键、找不到就忽略）。删除被关联目标时链接**不删除、不级联**，
 * 读取时**容忍**（不报错、不删笔记），就地呈现时忽略该关联；列表页可提示「关联目标已不存在」
 * 并允许一键清除该关联（§5）。**不做任何后台清理**。
 *
 * [noteId] 是唯一强制关系：删除笔记时其 links 由 FK **级联删除**。
 */
@Entity(
    tableName = "care_note_links",
    foreignKeys = [
        ForeignKey(
            entity = CareNote::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("noteId"),
        Index(value = ["targetType", "targetId"]),
    ],
)
data class CareNoteLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属笔记，NOT NULL + FK CASCADE（笔记侧唯一强制关系）。 */
    val noteId: Long,
    /** 目标类型，见 [CareNoteTargetType]；不含 `MEMBER`。 */
    val targetType: String,
    /** 目标记录 id；不是外键、不做级联（悬挂容忍，§5）。 */
    val targetId: Long,
)

/**
 * 关联目标类型（docs/care-notes.md §0.2 第 3 条）：只有 `MEDICATION` / `CARE_TASK` / `TODO`。
 * 刻意不保留 `MEMBER`——无 link 的笔记自然就是成员级通用笔记。
 */
object CareNoteTargetType {
    const val MEDICATION = "MEDICATION"
    const val CARE_TASK = "CARE_TASK"
    const val TODO = "TODO"

    val all = listOf(MEDICATION, CARE_TASK, TODO)
}
