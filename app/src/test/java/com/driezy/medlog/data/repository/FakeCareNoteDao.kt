package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareNoteDao
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteLink
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 照护笔记 DAO 的内存假件：成员作用域不在这里过滤（由仓库负责），只按 recipientId / status /
 * 关键词做与真 DAO 同构的查询与排序，便于在 JVM 上验证仓库的成员收口、状态流转与悬挂容忍。
 */
class FakeCareNoteDao : CareNoteDao {

    private val notes = MutableStateFlow<List<CareNote>>(emptyList())
    private val links = MutableStateFlow<List<CareNoteLink>>(emptyList())
    private val existingTargets = mutableMapOf<String, MutableSet<Long>>(
        CareNoteTargetType.MEDICATION to mutableSetOf(),
        CareNoteTargetType.CARE_TASK to mutableSetOf(),
        CareNoteTargetType.TODO to mutableSetOf(),
    )

    /** 人员 id → 归属成员 id；用于校验保存笔记时的人员存在性与成员归属。 */
    private val people = mutableMapOf<Long, Long>()
    private var nextNoteId = 1L
    private var nextLinkId = 1L

    fun seed(note: CareNote): Long {
        val id = if (note.id != 0L) note.id else nextNoteId++
        notes.value = notes.value + note.copy(id = id)
        if (id >= nextNoteId) nextNoteId = id + 1
        return id
    }

    /** 声明某人存在且属于某成员。 */
    fun seedPerson(personId: Long, recipientId: Long) {
        people[personId] = recipientId
    }

    fun removePerson(personId: Long) {
        people.remove(personId)
    }

    fun seedLink(noteId: Long, targetType: String, targetId: Long): Long {
        val id = nextLinkId++
        links.value = links.value + CareNoteLink(id = id, noteId = noteId, targetType = targetType, targetId = targetId)
        return id
    }

    /** 声明某目标仍然存在；未声明即视为不存在（悬挂）。 */
    fun seedTarget(targetType: String, targetId: Long) {
        existingTargets.getValue(targetType).add(targetId)
    }

    fun removeTarget(targetType: String, targetId: Long) {
        existingTargets.getValue(targetType).remove(targetId)
    }

    fun stored(): List<CareNote> = notes.value

    fun storedById(id: Long): CareNote? = notes.value.firstOrNull { it.id == id }

    fun storedLinks(): List<CareNoteLink> = links.value

    override fun getDefaultNotes(recipientId: Long): Flow<List<CareNote>> = notes.map { list ->
        list.filter { it.careRecipientId == recipientId && it.status != CareNoteStatus.SUPERSEDED }.sortedWith(ORDER)
    }

    override fun getAllNotes(recipientId: Long): Flow<List<CareNote>> = notes.map { list ->
        list.filter { it.careRecipientId == recipientId }.sortedWith(ORDER)
    }

    override suspend fun getDefaultNotesOnce(recipientId: Long): List<CareNote> =
        notes.value.filter { it.careRecipientId == recipientId && it.status != CareNoteStatus.SUPERSEDED }
            .sortedWith(ORDER)

    override fun searchNotes(recipientId: Long, query: String): Flow<List<CareNote>> = notes.map { list ->
        list.filter { note ->
            note.careRecipientId == recipientId &&
                (
                    note.title.contains(query) ||
                        note.body.contains(query) ||
                        (note.attributionName?.contains(query) == true)
                    )
        }.sortedWith(ORDER)
    }

    override fun getNotesForTarget(recipientId: Long, targetType: String, targetId: Long): Flow<List<CareNote>> =
        notes.map { list ->
            val noteIds = links.value
                .filter { it.targetType == targetType && it.targetId == targetId }
                .map { it.noteId }
                .toSet()
            list.filter { it.careRecipientId == recipientId && it.id in noteIds }.sortedWith(ORDER)
        }

    override suspend fun getById(id: Long): CareNote? = notes.value.firstOrNull { it.id == id }

    override suspend fun insert(note: CareNote): Long {
        val id = nextNoteId++
        notes.value = notes.value + note.copy(id = id)
        return id
    }

    override suspend fun update(note: CareNote) {
        notes.value = notes.value.map { if (it.id == note.id) note else it }
    }

    override suspend fun deleteById(id: Long) {
        notes.value = notes.value.filterNot { it.id == id }
        // 真 DAO 由 FK CASCADE 删除 links；这里手工模拟同一语义。
        links.value = links.value.filterNot { it.noteId == id }
    }

    override suspend fun insertLink(link: CareNoteLink): Long {
        val id = nextLinkId++
        links.value = links.value + link.copy(id = id)
        return id
    }

    override suspend fun deleteLinkById(linkId: Long) {
        links.value = links.value.filterNot { it.id == linkId }
    }

    override suspend fun linksForNote(noteId: Long): List<CareNoteLink> =
        links.value.filter { it.noteId == noteId }.sortedBy { it.id }

    override suspend fun findLink(noteId: Long, targetType: String, targetId: Long): CareNoteLink? =
        links.value.firstOrNull { it.noteId == noteId && it.targetType == targetType && it.targetId == targetId }

    override suspend fun medicationExists(id: Long): Int =
        if (id in existingTargets.getValue(CareNoteTargetType.MEDICATION)) 1 else 0

    override suspend fun careTaskExists(id: Long): Int =
        if (id in existingTargets.getValue(CareNoteTargetType.CARE_TASK)) 1 else 0

    override suspend fun todoExists(id: Long): Int =
        if (id in existingTargets.getValue(CareNoteTargetType.TODO)) 1 else 0

    override suspend fun personBelongsToRecipient(id: Long, recipientId: Long): Int =
        if (people[id] == recipientId) 1 else 0

    private companion object {
        /** 与 DAO SQL 同构：QUESTIONABLE > ACTIVE > SUPERSEDED，再 updatedAtMs 倒序（空在后），再 createdAtMs 倒序、id 倒序。 */
        val ORDER: Comparator<CareNote> = Comparator { a, b ->
            val byStatus = statusRank(a.status).compareTo(statusRank(b.status))
            if (byStatus != 0) return@Comparator byStatus
            val byUpdated = (b.updatedAtMs ?: Long.MIN_VALUE).compareTo(a.updatedAtMs ?: Long.MIN_VALUE)
            if (byUpdated != 0) return@Comparator byUpdated
            val byCreated = b.createdAtMs.compareTo(a.createdAtMs)
            if (byCreated != 0) return@Comparator byCreated
            b.id.compareTo(a.id)
        }

        fun statusRank(status: String): Int = when (status) {
            CareNoteStatus.QUESTIONABLE -> 0
            CareNoteStatus.ACTIVE -> 1
            else -> 2
        }
    }
}
