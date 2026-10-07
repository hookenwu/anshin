package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteLink
import kotlinx.coroutines.flow.Flow

/** 编辑器挂接目标（type + id）；不含 `MEMBER`（成员归属由 careRecipientId 决定，docs/care-notes.md §4）。 */
data class CareNoteTarget(val targetType: String, val targetId: Long)

/**
 * 笔记 + 其悬挂关联（目标已不存在的 link）。
 *
 * [danglingLinks] 由读取时计算（容忍、不删笔记、不做后台清理），供列表页提示
 * 「关联目标已不存在」并允许一键清除该关联（docs/care-notes.md §5）。
 */
data class CareNoteWithState(val note: CareNote, val danglingLinks: List<CareNoteLink>)

/**
 * 照护笔记 SSOT 仓库（docs/care-notes.md §4/§5/§6）。
 *
 * 与 CareTodoRepository 同一套成员维度约定：查询按「当前家庭成员」过滤，写入前绑定当前成员；
 * 未选择成员时读返回空、写直接报错。[setStatus] 是状态机的唯一落库入口（**只有用户能设置状态**）。
 * 不改写 [CareNote.body]（App 不改写正文，§1）。
 */
interface CareNoteRepository {

    /**
     * 普通列表（[query] 为空）：默认 `ACTIVE` + `QUESTIONABLE`，`SUPERSEDED` 折叠/隐藏（§6）。
     * 主动关键词搜索（[query] 非空）：匹配 title/body/attributionName，**仍返回命中的 `SUPERSEDED`**。
     */
    fun observeNotes(query: String = ""): Flow<List<CareNoteWithState>>

    /** 就地呈现：某目标（药/照护事项/待办）相关的笔记；含 `SUPERSEDED`（默认折叠，§7）。 */
    fun observeRelatedNotes(targetType: String, targetId: Long): Flow<List<CareNote>>

    suspend fun getNoteById(id: Long): CareNote?

    suspend fun linksForNote(noteId: Long): List<CareNoteLink>

    /** 新建笔记；身份与 createdAtMs 由仓库盖章，状态恒为 `ACTIVE`。 */
    suspend fun createNote(
        title: String,
        body: String,
        attributionType: String,
        attributionName: String? = null,
        attributionAtMs: Long? = null,
        attributionText: String? = null,
        links: List<CareNoteTarget> = emptyList(),
    ): Long

    /** 编辑内容与挂接目标；身份、状态与 createdAtMs 保持不变，updatedAtMs 刷新。 */
    suspend fun updateNote(
        id: Long,
        title: String,
        body: String,
        attributionType: String,
        attributionName: String? = null,
        attributionAtMs: Long? = null,
        attributionText: String? = null,
        links: List<CareNoteTarget> = emptyList(),
    )

    /**
     * 切换状态（**只能由用户调用**；App 不推断、不自动过期）。切到 `SUPERSEDED` 时写入
     * supersededText/At，切走时清空二者（避免残留两套关闭语义）。
     */
    suspend fun setStatus(id: Long, status: String, supersededText: String? = null, supersededAtMs: Long? = null)

    suspend fun addLink(noteId: Long, targetType: String, targetId: Long)

    suspend fun removeLink(linkId: Long)

    /** 一键清除该笔记的所有悬挂关联（目标已不存在）；只在用户显式调用时执行，无后台清理。 */
    suspend fun removeDanglingLinks(noteId: Long)

    suspend fun deleteNote(id: Long)

    /** 目标是否已不存在（悬挂容忍读取用；不建外键、不清扫，§5）。 */
    suspend fun isTargetMissing(targetType: String, targetId: Long): Boolean
}
