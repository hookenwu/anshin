package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CarePerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 人员档案仓储的内存假件：成员作用域在单测里不参与（VM 测试只关心选择器/快速新增的状态），
 * 所有读直接返回当前集合，写就地更新并分配自增 id。
 */
class FakeCarePersonRepository : CarePersonRepository {

    private val people = MutableStateFlow<List<CarePerson>>(emptyList())
    private val noteCounts = mutableMapOf<Long, Int>()
    private var nextId = 1L

    /** 决定写入时间戳的「当前时间」；测试可显式设置。 */
    var clockMs: Long = 1_000L

    /** 打开后，所有写操作抛错，用于验证 UI/VM 的错误分支。 */
    var failWrites: Boolean = false

    fun seed(person: CarePerson): Long {
        val id = if (person.id != 0L) person.id else nextId++
        people.value = people.value + person.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    fun setNoteCount(personId: Long, count: Int) {
        noteCounts[personId] = count
    }

    fun stored(): List<CarePerson> = people.value

    fun storedById(id: Long): CarePerson? = people.value.firstOrNull { it.id == id }

    override fun observePeople(): Flow<List<CarePerson>> = people.map { list -> list.sortedBy { it.name } }

    override fun searchPeople(query: String): Flow<List<CarePerson>> {
        val trimmed = query.trim()
        return people.map { list ->
            list.filter { trimmed.isEmpty() || it.name.contains(trimmed) }.sortedBy { it.name }
        }
    }

    override suspend fun getPersonById(id: Long): CarePerson? = storedById(id)

    override suspend fun createPerson(
        name: String,
        gender: String?,
        approxAge: Int?,
        hospital: String?,
        agency: String?,
        phone: String?,
    ): Long {
        maybeFail()
        return seed(
            CarePerson(
                careRecipientId = 1L,
                name = name,
                gender = gender,
                approxAge = approxAge,
                hospital = hospital,
                agency = agency,
                phone = phone,
                createdAtMs = clockMs,
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
        maybeFail()
        people.value = people.value.map {
            if (it.id == id) {
                it.copy(
                    name = name,
                    gender = gender,
                    approxAge = approxAge,
                    hospital = hospital,
                    agency = agency,
                    phone = phone,
                    updatedAtMs = clockMs,
                )
            } else {
                it
            }
        }
    }

    override suspend fun deletePerson(id: Long) {
        maybeFail()
        people.value = people.value.filterNot { it.id == id }
    }

    override suspend fun countNotesReferencing(personId: Long): Int = noteCounts[personId] ?: 0

    override suspend fun personBelongsToCurrentRecipient(personId: Long): Boolean =
        people.value.any { it.id == personId }

    private fun maybeFail() {
        if (failWrites) error("write failed")
    }
}
