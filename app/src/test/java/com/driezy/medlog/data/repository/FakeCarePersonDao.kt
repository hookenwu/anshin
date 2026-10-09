package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.CarePersonDao
import com.driezy.medlog.data.model.CarePerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 人员档案 DAO 的内存假件：成员作用域不在这里过滤（由仓库负责），只按 recipientId / 姓名
 * 做与真 DAO 同构的查询与排序，便于在 JVM 上验证仓库的成员收口与 CRUD。
 */
class FakeCarePersonDao : CarePersonDao {

    private val people = MutableStateFlow<List<CarePerson>>(emptyList())
    private val noteCounts = mutableMapOf<Long, Int>()
    private var nextId = 1L

    fun seed(person: CarePerson): Long {
        val id = if (person.id != 0L) person.id else nextId++
        people.value = people.value + person.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    /** 声明某个人员被 N 条笔记引用（删除提示用）。 */
    fun setNoteCount(personId: Long, count: Int) {
        noteCounts[personId] = count
    }

    fun stored(): List<CarePerson> = people.value

    fun storedById(id: Long): CarePerson? = people.value.firstOrNull { it.id == id }

    override fun getPeople(recipientId: Long): Flow<List<CarePerson>> = people.map { list ->
        list.filter { it.careRecipientId == recipientId }.sortedBy { it.name }
    }

    override fun searchPeople(recipientId: Long, query: String): Flow<List<CarePerson>> = people.map { list ->
        list.filter { it.careRecipientId == recipientId && it.name.contains(query) }.sortedBy { it.name }
    }

    override suspend fun getById(id: Long): CarePerson? = storedById(id)

    override suspend fun countBelongingTo(id: Long, recipientId: Long): Int =
        if (people.value.any { it.id == id && it.careRecipientId == recipientId }) 1 else 0

    override suspend fun countNotesReferencing(recipientId: Long, personId: Long): Int = noteCounts[personId] ?: 0

    override suspend fun insert(person: CarePerson): Long {
        val id = nextId++
        people.value = people.value + person.copy(id = id)
        return id
    }

    override suspend fun update(person: CarePerson) {
        people.value = people.value.map { if (it.id == person.id) person else it }
    }

    override suspend fun deleteById(id: Long) {
        people.value = people.value.filterNot { it.id == id }
    }
}
