package com.driezy.medlog.feature.medications.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationSortOrder
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.medications.application.MedicationStatusDerivation
import com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

internal data class MyMedicationsState(
    val medications: List<Medication> = emptyList(),
    val sortOrder: MedicationSortOrder = MedicationSortOrder.DEFAULT,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** 每药的**活状态 kind**（docs/medication-status-spec.md §5.4）：用于行内「暂停中 / 已归档」角标。 */
    val statusKinds: Map<Long, String?> = emptyMap(),
)

@HiltViewModel
class MyMedicationsViewModel @Inject constructor(
    private val repository: MedicationRepository,
    private val preferences: UserPreferencesRepository,
    private val statusRepository: MedicationStatusRepository,
    private val setStatus: SetMedicationStatusUseCase,
    private val clock: Clock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val state = combine(
        refresh.flatMapLatest { repository.getAllMedications() },
        preferences.settingsFlow.map { it.medicationSortOrder }.distinctUntilChanged(),
        statusRepository.observeAllEvents(),
    ) { medications, sortOrder, events ->
        val byMedication = events.groupBy { it.medicationId }
        val now = clock.millis()
        MyMedicationsState(
            medications = medications.sortedFor(sortOrder),
            sortOrder = sortOrder,
            loading = false,
            statusKinds = medications.associate { medication ->
                medication.id to MedicationStatusDerivation.liveStatusKind(byMedication[medication.id].orEmpty(), now)
            },
        )
    }
        .catch { emit(MyMedicationsState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyMedicationsState())

    /** 排序档位是持久化偏好：选一次即全局生效，重进 App 仍保留。 */
    fun onSortOrderChange(order: MedicationSortOrder) {
        viewModelScope.launch { preferences.updateMedicationSortOrder(order) }
    }

    /**
     * 批量生效日期，写入 `medication_status_events` 的**成员提醒时区**本地日界（§E11）。
     * 一次事务内为全部选中药品写事件 + 缓存；未来生效日由用例拒绝（D11）。
     */
    fun applyStatus(medicationIds: List<Long>, kind: String, effectiveDate: LocalDate, reason: String?) {
        if (medicationIds.isEmpty()) return
        viewModelScope.launch {
            runCatching { setStatus.applyBatch(medicationIds, kind, effectiveDate, reason) }
        }
    }

    fun retry() {
        refresh.update { it + 1 }
    }
}
