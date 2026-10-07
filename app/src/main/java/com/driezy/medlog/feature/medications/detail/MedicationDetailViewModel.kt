package com.driezy.medlog.feature.medications.detail

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import com.driezy.medlog.feature.medications.application.ObserveMedicationAdherence
import com.driezy.medlog.feature.medications.application.accumulatedDoseUsage
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

data class DetailUiState(
    val medication: Medication? = null,
    val logs: List<MedicationLog> = emptyList(),
    /** 近30天服药坚持率 */
    val adherence30d: Float = 0f,
    /** 近30天已服次数 */
    val taken30d: Int = 0,
    val partial30d: Int = 0,
    /** 近30天计划次数 */
    val total30d: Int = 0,
    /** 累计用量文本（如 `0.5 g` / `4 粒`），无服药记录时为 null。 */
    val accumulatedUsage: String? = null,
    /** 当前库存占初始设置的比率（0-1） */
    val isLoading: Boolean = true,
    val error: Boolean = false,
    val isSaving: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** 就地「相关笔记」：该药为 target 的 CareNote（空则不渲染，docs/care-notes.md §7）。 */
    val relatedNotes: List<CareNote> = emptyList(),
)

sealed interface DetailUiAction {
    data class Load(val medicationId: Long) : DetailUiAction
    data object RefreshTime : DetailUiAction
    data object Archive : DetailUiAction
    data object Delete : DetailUiAction
    data class AdjustStock(val delta: Double) : DetailUiAction
}

sealed interface DetailUiEffect {
    data object NavigateBack : DetailUiEffect
}

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    private val medicationRepo: MedicationRepository,
    private val reconcileReminders: ReconcileRemindersUseCase,
    private val clock: Clock,
    private val observeAdherence: ObserveMedicationAdherence,
    private val transactions: TransactionRunner,
    private val careNoteRepository: CareNoteRepository,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()
    private val effectChannel = Channel<DetailUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val time = MutableStateFlow(clock.instant())
    private var observation: Job? = null
    private var notesObservation: Job? = null

    fun onAction(action: DetailUiAction) {
        when (action) {
            is DetailUiAction.Load -> loadMedication(action.medicationId)
            DetailUiAction.RefreshTime -> time.value = clock.instant()
            DetailUiAction.Archive -> archiveMedication()
            DetailUiAction.Delete -> deleteMedication()
            is DetailUiAction.AdjustStock -> adjustStock(action.delta)
        }
    }

    fun loadMedication(id: Long) {
        observation?.cancel()
        observation = viewModelScope.launch {
            observeAdherence(time, id)
                .catch { _uiState.update { it.copy(isLoading = false, error = true) } }
                .collect { summary ->
                    val medication = summary.medications.firstOrNull()
                    _uiState.update {
                        it.copy(
                            zone = summary.zone,
                            medication = medication,
                            logs = summary.logs.sortedByDescending { log ->
                                log.scheduledTimeMs
                            },
                            accumulatedUsage = medication?.let { med ->
                                accumulatedDoseUsage(med, summary.logs).formatted()
                            },
                            taken30d = summary.taken30d,
                            partial30d = summary.partial30d,
                            total30d = summary.total30d,
                            adherence30d = summary.rate30d,
                            isLoading = false,
                            error = false,
                        )
                    }
                }
        }
        // 就地「相关笔记」：与该药关联的笔记（只读呈现，空则不渲染）。
        notesObservation?.cancel()
        notesObservation = viewModelScope.launch {
            careNoteRepository.observeRelatedNotes(CareNoteTargetType.MEDICATION, id)
                .catch { /* 笔记读取失败不影响药品详情主流程 */ }
                .collect { notes -> _uiState.update { it.copy(relatedNotes = notes) } }
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, error = false) }
        safeLaunch(onError = { _uiState.update { it.copy(error = true) } }) {
            try {
                block()
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun archiveMedication() {
        val medication = _uiState.value.medication ?: return
        val id = medication.id
        mutate {
            if (medication.isArchived) {
                medicationRepo.unarchiveMedication(id)
            } else {
                medicationRepo.archiveMedication(id)
            }
            reconcileReminders.medication(MedicationId(id), ReminderReconcileReason.MEDICATION_CHANGED)
            effectChannel.send(DetailUiEffect.NavigateBack)
        }
    }

    fun deleteMedication() {
        val med = _uiState.value.medication ?: return
        mutate {
            medicationRepo.deleteMedication(med)
            reconcileReminders.medication(MedicationId(med.id), ReminderReconcileReason.MEDICATION_CHANGED)
            effectChannel.send(DetailUiEffect.NavigateBack)
        }
    }

    /** 快捷调整库存，delta > 0 补药，< 0 扩展消耗 */
    fun adjustStock(delta: Double) {
        val med = _uiState.value.medication ?: return
        if (!delta.isFinite()) return
        mutate {
            transactions.withTransaction {
                val currentStock = medicationRepo.getMedicationById(med.id)?.stock ?: return@withTransaction
                medicationRepo.updateStock(med.id, (currentStock + delta).coerceAtLeast(0.0))
            }
            reconcileReminders.medication(MedicationId(med.id), ReminderReconcileReason.MEDICATION_CHANGED)
            // 重载最新状态
            val updated = medicationRepo.getMedicationById(med.id)
            _uiState.value = _uiState.value.copy(medication = updated)
        }
    }
}
