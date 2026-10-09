package com.driezy.medlog.feature.carepeople

import com.driezy.medlog.data.model.CarePerson

/**
 * 人员档案管理面 UI 状态（docs/care-people.md §4.2）。
 *
 * 列表由仓库按「当前成员」作用域产出；[query] 为空 → 全部人员；非空 → 按姓名搜索。
 * 删除走既有破坏性确认惯用法，且**提示引用该人员的笔记数但不阻断删除**（§9.2）。
 */
data class CarePeopleUiState(
    val people: List<CarePerson> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    /** 非空时为新增/编辑表单。 */
    val editor: PersonForm? = null,
    /** 非空时为待删除人员（含引用笔记数）。 */
    val pendingDelete: PersonDeleteRequest? = null,
)

/** 新增/编辑表单；[id] 为空代表新增。 */
data class PersonForm(
    val id: Long? = null,
    val name: String = "",
    val gender: String = "",
    val approxAge: String = "",
    val hospital: String = "",
    val agency: String = "",
    val phone: String = "",
)

/** 待删除人员：姓名用于展示，[noteCount] 用于「有 N 条笔记记录了此人」提示。 */
data class PersonDeleteRequest(val id: Long, val name: String, val noteCount: Int)

sealed interface CarePeopleUiAction {
    data class QueryChanged(val query: String) : CarePeopleUiAction

    data object AddClicked : CarePeopleUiAction

    data class EditClicked(val personId: Long) : CarePeopleUiAction

    data object FormDismissed : CarePeopleUiAction

    data class FormChanged(val form: PersonForm) : CarePeopleUiAction

    data object FormSaved : CarePeopleUiAction

    data class DeleteClicked(val personId: Long) : CarePeopleUiAction

    data object DeleteConfirmed : CarePeopleUiAction

    data object DeleteDismissed : CarePeopleUiAction
}

sealed interface CarePeopleUiEffect {
    data class Failed(val message: String?) : CarePeopleUiEffect
}
