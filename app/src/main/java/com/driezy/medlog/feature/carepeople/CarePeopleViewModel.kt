package com.driezy.medlog.feature.carepeople

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.repository.CarePersonRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 人员档案管理面 ViewModel（docs/care-people.md §4.2/P3）。
 *
 * 全部命令经仓库收口（成员隔离、姓名必填）；搜索词经 [flatMapLatest] 驱动仓库查询。
 * 删除人员**不阻断**：先查出引用笔记数用于提示，确认后删除。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CarePeopleViewModel @Inject constructor(private val repository: CarePersonRepository) : BaseViewModel() {

    private val query = MutableStateFlow("")
    private val editor = MutableStateFlow<PersonForm?>(null)
    private val pendingDelete = MutableStateFlow<PersonDeleteRequest?>(null)

    private val effectChannel = Channel<CarePeopleUiEffect>(Channel.BUFFERED)
    val uiEffect = effectChannel.receiveAsFlow()

    private val rows = query.flatMapLatest { current -> repository.searchPeople(current) }

    val uiState = combine(rows, query, editor, pendingDelete) { people, current, form, delete ->
        CarePeopleUiState(people = people, query = current, isLoading = false, editor = form, pendingDelete = delete)
    }
        .catch { error ->
            effectChannel.send(CarePeopleUiEffect.Failed(error.localizedMessage))
            emit(CarePeopleUiState(isLoading = false, failed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarePeopleUiState())

    fun onAction(action: CarePeopleUiAction) {
        when (action) {
            is CarePeopleUiAction.QueryChanged -> query.value = action.query
            CarePeopleUiAction.AddClicked -> editor.value = PersonForm()
            is CarePeopleUiAction.EditClicked -> loadForEdit(action.personId)
            CarePeopleUiAction.FormDismissed -> editor.value = null
            is CarePeopleUiAction.FormChanged -> editor.value = action.form
            CarePeopleUiAction.FormSaved -> save()
            is CarePeopleUiAction.DeleteClicked -> requestDelete(action.personId)
            CarePeopleUiAction.DeleteConfirmed -> confirmDelete()
            CarePeopleUiAction.DeleteDismissed -> pendingDelete.value = null
        }
    }

    private fun loadForEdit(personId: Long) {
        safeLaunch(onError = { error -> effectChannel.trySend(CarePeopleUiEffect.Failed(error.localizedMessage)) }) {
            val person = repository.getPersonById(personId) ?: return@safeLaunch
            editor.value = PersonForm(
                id = person.id,
                name = person.name,
                gender = person.gender.orEmpty(),
                approxAge = person.approxAge?.toString().orEmpty(),
                hospital = person.hospital.orEmpty(),
                agency = person.agency.orEmpty(),
                phone = person.phone.orEmpty(),
            )
        }
    }

    private fun save() {
        val form = editor.value ?: return
        if (form.name.isBlank()) {
            effectChannel.trySend(CarePeopleUiEffect.Failed(null))
            return
        }
        safeLaunch(onError = { error -> effectChannel.trySend(CarePeopleUiEffect.Failed(error.localizedMessage)) }) {
            val age = form.approxAge.trim().toIntOrNull()
            if (form.id == null) {
                repository.createPerson(
                    name = form.name,
                    gender = form.gender.ifBlank { null },
                    approxAge = age,
                    hospital = form.hospital.ifBlank { null },
                    agency = form.agency.ifBlank { null },
                    phone = form.phone.ifBlank { null },
                )
            } else {
                repository.updatePerson(
                    id = form.id,
                    name = form.name,
                    gender = form.gender.ifBlank { null },
                    approxAge = age,
                    hospital = form.hospital.ifBlank { null },
                    agency = form.agency.ifBlank { null },
                    phone = form.phone.ifBlank { null },
                )
            }
            editor.value = null
        }
    }

    /** 删除前查出引用笔记数用于提示；**不阻断删除**（docs/care-people.md §9.2）。 */
    private fun requestDelete(personId: Long) {
        safeLaunch(onError = { error -> effectChannel.trySend(CarePeopleUiEffect.Failed(error.localizedMessage)) }) {
            val person = repository.getPersonById(personId) ?: return@safeLaunch
            val count = repository.countNotesReferencing(personId)
            pendingDelete.value = PersonDeleteRequest(person.id, person.name, count)
        }
    }

    private fun confirmDelete() {
        val request = pendingDelete.value ?: return
        safeLaunch(onError = { error -> effectChannel.trySend(CarePeopleUiEffect.Failed(error.localizedMessage)) }) {
            repository.deletePerson(request.id)
            pendingDelete.value = null
        }
    }
}
