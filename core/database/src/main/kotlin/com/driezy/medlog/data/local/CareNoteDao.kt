package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteLink
import com.driezy.medlog.data.model.CareNoteStatus
import kotlinx.coroutines.flow.Flow

/**
 * 照护笔记 DAO（docs/care-notes.md §4/§5/§6）。
 *
 * 与 CareTodoDao 同一套成员维度约定：所有查询显式接收 recipientId，由仓库层注入「当前成员」，
 * DAO 本身不认识这一概念。状态流转由仓库改写整行后走 [update]，不在 DAO 里堆散装 UPDATE。
 *
 * 排序：先按 `status`（`QUESTIONABLE` > `ACTIVE` > `SUPERSEDED`）再按 `updatedAtMs` 倒序
 * （`updatedAtMs` 为空则自然排在已更新记录之后，再以 `createdAtMs` 倒序兜底，§6）。
 */
@Dao
interface CareNoteDao {

    /** 普通列表默认可见：`ACTIVE` + `QUESTIONABLE`（`SUPERSEDED` 折叠/隐藏）。 */
    @Query(
        "SELECT * FROM care_notes WHERE careRecipientId = :recipientId AND status != '${CareNoteStatus.SUPERSEDED}' " +
            "ORDER BY CASE status WHEN 'QUESTIONABLE' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "updatedAtMs DESC, createdAtMs DESC, id DESC",
    )
    fun getDefaultNotes(recipientId: Long): Flow<List<CareNote>>

    /** 全量（含 `SUPERSEDED`），供一次性读取/测试。 */
    @Query(
        "SELECT * FROM care_notes WHERE careRecipientId = :recipientId " +
            "ORDER BY CASE status WHEN 'QUESTIONABLE' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "updatedAtMs DESC, createdAtMs DESC, id DESC",
    )
    fun getAllNotes(recipientId: Long): Flow<List<CareNote>>

    @Query(
        "SELECT * FROM care_notes WHERE careRecipientId = :recipientId AND status != '${CareNoteStatus.SUPERSEDED}' " +
            "ORDER BY CASE status WHEN 'QUESTIONABLE' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "updatedAtMs DESC, createdAtMs DESC, id DESC",
    )
    suspend fun getDefaultNotesOnce(recipientId: Long): List<CareNote>

    /**
     * 主动关键词搜索：匹配 `title` / `body` / `attributionName`（Room LIKE，无 FTS，§6）。
     * **仍返回命中的 `SUPERSEDED`**，由展示层标记「已被更新」，避免造成记录丢失的错觉。
     */
    @Query(
        "SELECT * FROM care_notes WHERE careRecipientId = :recipientId AND (" +
            "title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' " +
            "OR (attributionName IS NOT NULL AND attributionName LIKE '%' || :query || '%')) " +
            "ORDER BY CASE status WHEN 'QUESTIONABLE' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "updatedAtMs DESC, createdAtMs DESC, id DESC",
    )
    fun searchNotes(recipientId: Long, query: String): Flow<List<CareNote>>

    /** 某目标（药/照护事项/待办）相关的笔记，就地卡片用；含 `SUPERSEDED`（默认折叠）。 */
    @Query(
        "SELECT n.* FROM care_notes n INNER JOIN care_note_links l ON l.noteId = n.id " +
            "WHERE n.careRecipientId = :recipientId AND l.targetType = :targetType AND l.targetId = :targetId " +
            "ORDER BY CASE n.status WHEN 'QUESTIONABLE' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, " +
            "n.updatedAtMs DESC, n.createdAtMs DESC, n.id DESC",
    )
    fun getNotesForTarget(recipientId: Long, targetType: String, targetId: Long): Flow<List<CareNote>>

    @Query("SELECT * FROM care_notes WHERE id = :id")
    suspend fun getById(id: Long): CareNote?

    @Insert
    suspend fun insert(note: CareNote): Long

    @Update
    suspend fun update(note: CareNote)

    @Query("DELETE FROM care_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ── links ────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertLink(link: CareNoteLink): Long

    @Query("DELETE FROM care_note_links WHERE id = :linkId")
    suspend fun deleteLinkById(linkId: Long)

    @Query("SELECT * FROM care_note_links WHERE noteId = :noteId ORDER BY id ASC")
    suspend fun linksForNote(noteId: Long): List<CareNoteLink>

    @Query(
        "SELECT * FROM care_note_links WHERE noteId = :noteId AND targetType = :targetType " +
            "AND targetId = :targetId LIMIT 1",
    )
    suspend fun findLink(noteId: Long, targetType: String, targetId: Long): CareNoteLink?

    // ── 目标存在性（悬挂容忍读取，§5；不建外键，不做后台清理）──────────────────

    @Query("SELECT COUNT(*) FROM medications WHERE id = :id")
    suspend fun medicationExists(id: Long): Int

    @Query("SELECT COUNT(*) FROM care_tasks WHERE id = :id")
    suspend fun careTaskExists(id: Long): Int

    @Query("SELECT COUNT(*) FROM care_todos WHERE id = :id")
    suspend fun todoExists(id: Long): Int
}
