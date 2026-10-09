package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 轻量人员档案（docs/care-people.md §1）——为照护笔记的「谁说的」提供可复选的人员，
 * 让重复输入同一个护工/护士/医生变快；**不扩展成通讯录**。
 *
 * 按 [CareRecipient] 隔离（[careRecipientId] 是成员归属的唯一事实源）；字段限定为
 * 姓名 + 少量可选信息。刻意**不加 `isArchived`**（原则 2）：不再使用的人**直接删除**，
 * 历史由笔记里的 `attributionName` 姓名快照保护（docs/care-people.md §2）。
 *
 * 人员是**归属来源**，不是「笔记所关于的对象」，因此**不进入 `care_note_links`**。
 */
@Entity(
    tableName = "care_people",
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
        Index("name"),
    ],
)
data class CarePerson(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 成员归属，NOT NULL + FK CASCADE（成员归属的唯一事实源）。 */
    val careRecipientId: Long,
    /** 姓名，必填；**用户输入什么就是什么，不做规范化**。 */
    val name: String,
    /** 性别（男 / 女 / 其他），可空。 */
    val gender: String? = null,
    /** 大致年龄（刻意不叫「年龄」、不逼精确值；仅做宽松范围校验），可空。 */
    val approxAge: Int? = null,
    /** 医院（自由文本），可空。 */
    val hospital: String? = null,
    /** 医护公司（自由文本），可空。 */
    val agency: String? = null,
    /** 联系电话（自由文本，仅存本机、不校验格式、不外发），可空。 */
    val phone: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long? = null,
)
