package com.driezy.medlog.feature.medications.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationSortOrder
import com.driezy.medlog.data.repository.MedicationStatusPreferences
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.feature.medications.application.MedicationStatusDerivation
import com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
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
    private val statusPreferences: MedicationStatusPreferences,
    private val setStatus: SetMedicationStatusUseCase,
    private val activeRecipient: ActiveRecipientStore,
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

    /**
     * 「今天尚未核对」提示（docs/medication-daily-reconcile.md T6）：读成员级 `lastUserCheckedDate`。
     *
     * 触发源 = 成员切换 ∪ `retry()` ∪ 状态事件变化 ∪ **核对日期标记变化**。最后一项是必需的：
     * 纯无变更的确认（裁决 1）**零状态事件**、只写 DataStore 标记，只观察事件的读者会漏掉它，
     * 提示会一直停在「今天尚未核对」。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val checkedInToday: StateFlow<Boolean> =
        activeRecipient.recipientId
            .flatMapLatest { recipientId ->
                combine(
                    refresh,
                    statusRepository.observeAllEvents(),
                ) { _, _ -> recipientId }
            }
            .map { checkedInNow(it) }
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private suspend fun checkedInNow(recipientId: Long): Boolean {
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return false
        val zone = preferences.settingsFlow.first().reminderZone(clock.zone)
        val today = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
        return statusPreferences.userCheckedDate(recipientId) == today.toEpochDay()
    }

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
