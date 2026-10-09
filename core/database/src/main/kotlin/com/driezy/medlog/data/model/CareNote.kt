package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 照护笔记（有归属、有日期、可被引用的照护参考记录；docs/care-notes.md §4）。
 *
 * 与 Medication / CareTask / CareTodo 平级的一等领域实体，挂在同一 CareRecipient 下：
 * 不排期、不打卡、不被「完成」，只在需要时被看到。`careRecipientId` 是「这条笔记属于谁」
 * 的**唯一事实源**（成员级通用笔记即没有任何 link 的笔记）。
 *
 * 硬规则（docs/care-notes.md §1）：App 不生成笔记内容、**不改写正文**、不把笔记转成结论——
 * [body] 永远是用户原文。刻意不加 `isArchived`（SUPERSEDED 已覆盖「过时但仍要留存」），
 * 也不加 `supersededByNoteId`（本期不做结构化替代链，§0.2 第 1 条）。
 */
@Entity(
    tableName = "care_notes",
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
data class CareNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 成员归属，NOT NULL + FK CASCADE（docs/care-notes.md §4）。 */
    val careRecipientId: Long,
    val title: String,
    /** 用户原文，App 在任何代码路径都不得改写（docs/care-notes.md §1）。 */
    val body: String,
    /** 归属类型，见 [CareNoteAttributionType]；默认为最保守的「个人观察」。 */
    val attributionType: String = CareNoteAttributionType.PERSONAL_OBSERVATION,
    /** 谁说的（护士张 / 王医生 / 护工 / 家人 / 自己 / 资料名），可空。 */
    val attributionName: String? = null,
    /** 何时说的，可空。 */
    val attributionAtMs: Long? = null,
    /** 出处细节（如「3 楼护士查房时提到」），可空。 */
    val attributionText: String? = null,
    /** 状态，见 [CareNoteStatus]；**只能由用户设置**，App 不推断、不自动过期。 */
    val status: String = CareNoteStatus.ACTIVE,
    /** 仅 `SUPERSEDED` 有意义：新的说法（自由文本，不做结构化替代链）。 */
    val supersededText: String? = null,
    /** 仅 `SUPERSEDED` 有意义：被更新的时间。 */
    val supersededAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long? = null,
    /**
     * 可选关联的人员档案（docs/care-people.md §1）。**刻意不建外键**：
     * 历史由 [attributionName] 姓名快照保护；删除人员后我们本就要**容忍悬挂并保留快照**；
     * 且本仓对跨实体引用一律「无外键 + 读取容忍」（`CareTodo.source*`、`care_note_links.targetId`）。
     * 不是身份事实源——成员归属仍由 [careRecipientId] 决定。
     */
    val attributionPersonId: Long? = null,
)

/**
 * 归属类型（docs/care-notes.md §2）。`attributionType` 在**任何展示面都必须可见**，
 * 即使 [CareNote.attributionName] 为空也不得出现无归属的裸正文。
 */
object CareNoteAttributionType {
    /** 医护交代：医生/护士的交代或医嘱解释。 */
    const val CLINICIAN = "CLINICIAN"

    /** 照护经验：护工/家属的经验做法。 */
    const val CAREGIVER_EXPERIENCE = "CAREGIVER_EXPERIENCE"

    /** 个人观察：我观察到的现象（中性呈现，绝不写成因果结论）。 */
    const val PERSONAL_OBSERVATION = "PERSONAL_OBSERVATION"

    /** 外部资料：书/文章/视频等（需标注来源名，不背书其正确性）。 */
    const val EXTERNAL_MATERIAL = "EXTERNAL_MATERIAL"

    val all = listOf(CLINICIAN, CAREGIVER_EXPERIENCE, PERSONAL_OBSERVATION, EXTERNAL_MATERIAL)
}

/**
 * 三态状态机（docs/care-notes.md §3）——状态**只能由用户设置**，App 不推断、不自动过期。
 *
 * - [ACTIVE]：仍适用（默认）；
 * - [QUESTIONABLE]：有待确认（例如观察待问医生），只表达不确定性，不派生行动；
 * - [SUPERSEDED]：已被后续医护意见更新（仍属历史，就地默认折叠到次级位置）。
 */
object CareNoteStatus {
    const val ACTIVE = "ACTIVE"
    const val QUESTIONABLE = "QUESTIONABLE"
    const val SUPERSEDED = "SUPERSEDED"

    val all = listOf(ACTIVE, QUESTIONABLE, SUPERSEDED)

    /** 普通列表默认可见：`ACTIVE` + `QUESTIONABLE`（`SUPERSEDED` 折叠/隐藏，§6）。 */
    val defaultVisible = listOf(QUESTIONABLE, ACTIVE)

    /** 默认折叠、仅在主动关键词搜索时返回（§0.2 第 4 条）。 */
    val folded = listOf(SUPERSEDED)
}
