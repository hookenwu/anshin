package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CareNoteDao
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteLink
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照护笔记仓库实现：成员维度收口，与 [CareTodoRepositoryImpl] 同一套约定。
 *
 * - 读在 `NO_RECIPIENT` 时不发射（等价空）；写在 `NO_RECIPIENT` 时直接报错；
 * - 悬挂关联在**读取时**计算并容忍（不报错、不删笔记）；清除只在用户显式调用时发生（无后台清理）；
 * - [CareNote.body] 永远原样落库，任何代码路径都不改写正文（docs/care-notes.md §1）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CareNoteRepositoryImpl @Inject constructor(
    private val careNoteDao: CareNoteDao,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : CareNoteRepository {

    private fun scopedNotes(block: (Long) -> Flow<List<CareNote>>): Flow<List<CareNote>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入照护笔记数据" }
        return recipientId
    }

    override fun observeNotes(query: String): Flow<List<CareNoteWithState>> {
        val trimmed = query.trim()
        val notes = if (trimmed.isEmpty()) {
            scopedNotes { careNoteDao.getDefaultNotes(it) }
        } else {
            scopedNotes { careNoteDao.searchNotes(it, trimmed) }
        }
        return notes.map { list ->
            list.map { note -> CareNoteWithState(note, danglingLinksFor(note.id)) }
        }
    }

    override fun observeRelatedNotes(targetType: String, targetId: Long): Flow<List<CareNote>> =
        scopedNotes { careNoteDao.getNotesForTarget(it, targetType, targetId) }

    override suspend fun getNoteById(id: Long): CareNote? = careNoteDao.getById(id)

    override suspend fun linksForNote(noteId: Long): List<CareNoteLink> = careNoteDao.linksForNote(noteId)

    override suspend fun createNote(
        title: String,
        body: String,
        attributionType: String,
        attributionName: String?,
        attributionAtMs: Long?,
        attributionText: String?,
        attributionPersonId: Long?,
        links: List<CareNoteTarget>,
    ): Long {
        val recipientId = requireRecipientId()
        val noteId = careNoteDao.insert(
            CareNote(
                careRecipientId = recipientId,
                title = title.trim(),
                // 正文原样落库：绝不 trim / 改写（docs/care-notes.md §1）。
                body = body,
                attributionType = attributionType,
                attributionName = attributionName.cleaned(),
                attributionAtMs = attributionAtMs,
                attributionText = attributionText.cleaned(),
                attributionPersonId = validatedPersonId(attributionPersonId, recipientId),
                status = CareNoteStatus.ACTIVE,
                createdAtMs = clock.millis(),
            ),
        )
        insertLinks(noteId, links)
        return noteId
    }

    override suspend fun updateNote(
        id: Long,
        title: String,
        body: String,
        attributionType: String,
        attributionName: String?,
        attributionAtMs: Long?,
        attributionText: String?,
        attributionPersonId: Long?,
        links: List<CareNoteTarget>,
    ) {
        val recipientId = requireRecipientId()
        val existing = careNoteDao.getById(id) ?: return
        careNoteDao.update(
            existing.copy(
                title = title.trim(),
                // 正文原样保存：绝不 trim / 改写。
                body = body,
                attributionType = attributionType,
                attributionName = attributionName.cleaned(),
                attributionAtMs = attributionAtMs,
                attributionText = attributionText.cleaned(),
                // 保存时二次校验：人员必须存在且属于当前成员，否则不写入关联（保留姓名快照）。
                attributionPersonId = validatedPersonId(attributionPersonId, recipientId),
                updatedAtMs = clock.millis(),
            ),
        )
        reconcileLinks(id, links)
    }

    override suspend fun setStatus(id: Long, status: String, supersededText: String?, supersededAtMs: Long?) {
        requireRecipientId()
        require(status in CareNoteStatus.all) { "非法的照护笔记状态：$status" }
        val existing = careNoteDao.getById(id) ?: return
        val superseded = status == CareNoteStatus.SUPERSEDED
        careNoteDao.update(
            existing.copy(
                status = status,
                supersededText = if (superseded) supersededText.cleaned() else null,
                supersededAtMs = if (superseded) (supersededAtMs ?: clock.millis()) else null,
                updatedAtMs = clock.millis(),
            ),
        )
    }

    override suspend fun addLink(noteId: Long, targetType: String, targetId: Long) {
        requireRecipientId()
        if (careNoteDao.findLink(noteId, targetType, targetId) != null) return
        careNoteDao.insertLink(CareNoteLink(noteId = noteId, targetType = targetType, targetId = targetId))
    }

    override suspend fun removeLink(linkId: Long) {
        requireRecipientId()
        careNoteDao.deleteLinkById(linkId)
    }

    override suspend fun removeDanglingLinks(noteId: Long) {
        requireRecipientId()
        danglingLinksFor(noteId).forEach { careNoteDao.deleteLinkById(it.id) }
    }

    override suspend fun deleteNote(id: Long) {
        requireRecipientId()
        careNoteDao.deleteById(id)
    }

    override suspend fun isTargetMissing(targetType: String, targetId: Long): Boolean = when (targetType) {
        CareNoteTargetType.MEDICATION -> careNoteDao.medicationExists(targetId) == 0
        CareNoteTargetType.CARE_TASK -> careNoteDao.careTaskExists(targetId) == 0
        CareNoteTargetType.TODO -> careNoteDao.todoExists(targetId) == 0
        // 未知目标类型不是本特性的目标；容忍并视为「不悬挂」，不抛错。
        else -> false
    }

    private suspend fun danglingLinksFor(noteId: Long): List<CareNoteLink> =
        careNoteDao.linksForNote(noteId).filter { isTargetMissing(it.targetType, it.targetId) }

    /**
     * 保存笔记时对人员关联做**二次校验**（docs/care-people.md §2 边界规则②③）：
     * 人员必须存在且属于当前成员，否则**不写入关联**（禁止跨成员关联），并保留姓名快照。
     *
     * 悬挂引用在读取时被容忍、**无后台清理**，因而在下一次保存时被确定性地清空；
     * 姓名快照由调用方原样传入，绝不被本方法改写。
     */
    private suspend fun validatedPersonId(personId: Long?, recipientId: Long): Long? =
        personId?.takeIf { careNoteDao.personBelongsToRecipient(it, recipientId) == 1 }

    private suspend fun insertLinks(noteId: Long, links: List<CareNoteTarget>) {
        links.distinct().forEach { target ->
            careNoteDao.insertLink(
                CareNoteLink(noteId = noteId, targetType = target.targetType, targetId = target.targetId),
            )
        }
    }

    /** 差量同步挂接目标：删除不再选中的、插入新选中的（保留未变动的）。 */
    private suspend fun reconcileLinks(noteId: Long, desired: List<CareNoteTarget>) {
        val existing = careNoteDao.linksForNote(noteId)
        val desiredKeys = desired.map { it.targetType to it.targetId }.toSet()
        existing.filter { (it.targetType to it.targetId) !in desiredKeys }.forEach {
            careNoteDao.deleteLinkById(it.id)
        }
        val existingKeys = existing.map { it.targetType to it.targetId }.toSet()
        desired.distinct()
            .filter { (it.targetType to it.targetId) !in existingKeys }
            .forEach { target ->
                careNoteDao.insertLink(
                    CareNoteLink(noteId = noteId, targetType = target.targetType, targetId = target.targetId),
                )
            }
    }

    /** 可选元数据清洗：空白视为未填写；不影响正文。 */
    private fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }
}
