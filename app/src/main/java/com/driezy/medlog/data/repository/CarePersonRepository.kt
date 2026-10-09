package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.CarePerson
import kotlinx.coroutines.flow.Flow

/**
 * 人员档案 SSOT 仓库（docs/care-people.md §1/§5）。
 *
 * 与 CareTodoRepository 同一套成员维度约定：查询按「当前家庭成员」过滤，写入前绑定当前成员；
 * 未选择成员时读返回空、写直接报错。人员查询**永远带 `careRecipientId`**（按成员隔离，
 * 不做跨成员共享）。
 *
 * 人员是**归属来源**而非「笔记所关于的对象」；仓库**不改写任何笔记**——人员改名/删除
 * 不影响既有笔记的正文、`attributionName` 或 `attributionType`（历史不可变，§2）。
 */
interface CarePersonRepository {

    /** 当前成员的全部人员（管理面列表）。 */
    fun observePeople(): Flow<List<CarePerson>>

    /** 选择器搜索：按姓名匹配（前缀/包含皆可），作用域为当前成员。 */
    fun searchPeople(query: String): Flow<List<CarePerson>>

    suspend fun getPersonById(id: Long): CarePerson?

    /** 新建人员；姓名必填，其余字段可选（快速新增只要求姓名）。 */
    suspend fun createPerson(
        name: String,
        gender: String? = null,
        approxAge: Int? = null,
        hospital: String? = null,
        agency: String? = null,
        phone: String? = null,
    ): Long

    /** 编辑人员；身份（careRecipientId/createdAtMs）保持不变，updatedAtMs 刷新。 */
    suspend fun updatePerson(
        id: Long,
        name: String,
        gender: String? = null,
        approxAge: Int? = null,
        hospital: String? = null,
        agency: String? = null,
        phone: String? = null,
    )

    /** 删除人员：档案行删除；笔记一行不动，其 `attributionPersonId` 成为悬挂指针（容忍读取）。 */
    suspend fun deletePerson(id: Long)

    /** 删除前提示用：当前成员下引用该人员的笔记数（不阻断删除）。 */
    suspend fun countNotesReferencing(personId: Long): Int

    /** 人员是否存在且属于当前成员（保存笔记时二次校验用）。 */
    suspend fun personBelongsToCurrentRecipient(personId: Long): Boolean
}
