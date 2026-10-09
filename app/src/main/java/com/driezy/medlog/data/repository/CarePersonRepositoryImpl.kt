package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CarePersonDao
import com.driezy.medlog.data.model.CarePerson
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 人员档案仓库实现：成员维度收口，与 [CareTodoRepositoryImpl] 同一套约定。
 *
 * - 读在 `NO_RECIPIENT` 时不发射（等价空）；写在 `NO_RECIPIENT` 时直接报错；
 * - 姓名必填且**原样存储，不做规范化**（docs/care-people.md §1）；
 * - 人员改名/删除**绝不改写任何笔记**（历史不可变）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CarePersonRepositoryImpl @Inject constructor(
    private val carePersonDao: CarePersonDao,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : CarePersonRepository {

    private fun scoped(block: (Long) -> Flow<List<CarePerson>>): Flow<List<CarePerson>> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    private suspend fun requireRecipientId(): Long {
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入人员档案数据" }
        return recipientId
    }

    override fun observePeople(): Flow<List<CarePerson>> = scoped { carePersonDao.getPeople(it) }

    override fun searchPeople(query: String): Flow<List<CarePerson>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) observePeople() else scoped { carePersonDao.searchPeople(it, trimmed) }
    }

    override suspend fun getPersonById(id: Long): CarePerson? = carePersonDao.getById(id)

    override suspend fun createPerson(
        name: String,
        gender: String?,
        approxAge: Int?,
        hospital: String?,
        agency: String?,
        phone: String?,
    ): Long {
        val recipientId = requireRecipientId()
        require(name.isNotBlank()) { "人员姓名必填" }
        return carePersonDao.insert(
            CarePerson(
                careRecipientId = recipientId,
                // 姓名原样存储（用户输入什么就是什么，不做规范化）。
                name = name,
                gender = gender.cleaned(),
                approxAge = approxAge,
                hospital = hospital.cleaned(),
                agency = agency.cleaned(),
                phone = phone.cleaned(),
                createdAtMs = clock.millis(),
            ),
        )
    }

    override suspend fun updatePerson(
        id: Long,
        name: String,
        gender: String?,
        approxAge: Int?,
        hospital: String?,
        agency: String?,
        phone: String?,
    ) {
        val recipientId = requireRecipientId()
        require(name.isNotBlank()) { "人员姓名必填" }
        val existing = carePersonDao.getById(id) ?: return
        // 只允许改当前成员自己的档案（成员隔离）。
        if (existing.careRecipientId != recipientId) return
        carePersonDao.update(
            existing.copy(
                name = name,
                gender = gender.cleaned(),
                approxAge = approxAge,
                hospital = hospital.cleaned(),
                agency = agency.cleaned(),
                phone = phone.cleaned(),
                updatedAtMs = clock.millis(),
            ),
        )
    }

    override suspend fun deletePerson(id: Long) {
        requireRecipientId()
        carePersonDao.deleteById(id)
    }

    override suspend fun countNotesReferencing(personId: Long): Int {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return 0
        return carePersonDao.countNotesReferencing(recipientId, personId)
    }

    override suspend fun personBelongsToCurrentRecipient(personId: Long): Boolean {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return false
        return carePersonDao.countBelongingTo(personId, recipientId) == 1
    }

    /** 可选字段清洗：空白视为未填写；姓名不做此处理。 */
    private fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }
}
