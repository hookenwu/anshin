package com.driezy.medlog.feature.medications.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationSortOrder
import com.driezy.medlog.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class MyMedicationsState(
    val medications: List<Medication> = emptyList(),
    val sortOrder: MedicationSortOrder = MedicationSortOrder.DEFAULT,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

@HiltViewModel
class MyMedicationsViewModel @Inject constructor(
    private val repository: MedicationRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val state = combine(
        refresh.flatMapLatest { repository.getAllMedications() },
        preferences.settingsFlow.map { it.medicationSortOrder }.distinctUntilChanged(),
    ) { medications, sortOrder ->
        MyMedicationsState(
            medications = medications.sortedFor(sortOrder),
            sortOrder = sortOrder,
            loading = false,
        )
    }
        .catch { emit(MyMedicationsState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyMedicationsState())

    /** 排序档位是持久化偏好：选一次即全局生效，重进 App 仍保留。 */
    fun onSortOrderChange(order: MedicationSortOrder) {
        viewModelScope.launch { preferences.updateMedicationSortOrder(order) }
    }

    fun retry() {
        refresh.update { it + 1 }
    }
}
