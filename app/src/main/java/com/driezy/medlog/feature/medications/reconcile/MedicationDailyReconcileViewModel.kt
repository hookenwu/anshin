package com.driezy.medlog.feature.medications.reconcile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.feature.medications.application.MedicationStatus
import com.driezy.medlog.feature.medications.application.MedicationStatusDerivation
import com.driezy.medlog.feature.medications.application.MedicationStatusWriteResult
import com.driezy.medlog.feature.medications.application.ReconcileAbortedException
import com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 核对页状态机：列表 → 摘要 → 结果（docs/medication-daily-reconcile.md §3.2）。 */
internal enum class ReconcileStep { LIST, SUMMARY, RESULT }

/** 一行 = 药 id、名称、复选框意图、当前是否 active、活状态 kind、是否迁移遗留。 */
internal data class ReconcileLine(
    val id: Long,
    val name: String,
    val checked: Boolean,
    val currentlyActive: Boolean,
    val liveKind: String?,
    val legacyUnknown: Boolean,
)

/** 提交前摘要（确认时点实时重算，§2 D7）。 */
internal data class ReconcileSummary(
    val toPause: List<Long>,
    val toResume: List<Long>,
    val unchangedCount: Int,
    val effectiveDay: LocalDate,
    val needsSecondConfirmation: Boolean,
)

internal data class ReconcileResult(val paused: Int, val resumed: Int, val unchanged: Int)

internal data class MedicationReconcileState(
    val step: ReconcileStep = ReconcileStep.LIST,
    val loading: Boolean = true,
    val lines: List<ReconcileLine> = emptyList(),
    val reason: String = "",
    val summary: ReconcileSummary? = null,
    val result: ReconcileResult? = null,
    /** 摘要构建时记录的**每一味**显示药的活状态 kind（事务内校验用，§4.3 / D13）。 */
    val expectedKinds: Map<Long, String?> = emptyMap(),
    /** 摘要因外部变更 / 跨零点被中止并重建（§3.2 项 4）。 */
    val stale: Boolean = false,
    /** 活动成员已切换，须按新成员重建（§7 E9）。 */
    val memberChanged: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 今日用药核对 ViewModel（docs/medication-daily-reconcile.md §4.2 / §4.3）。
 *
 * 只编排：列出全部药（含已停用）→ 按当前态预勾选 → 摘要 → 单事务 `applyReconcile`。
 * **绝不**写 `medication_logs`，**不**依赖任何日志仓储（§13 / MedicationReconcileInvarianceTest）。
 */
@HiltViewModel
class MedicationDailyReconcileViewModel @Inject constructor(
    private val medications: MedicationRepository,
    private val statusRepository: MedicationStatusRepository,
    private val setStatus: SetMedicationStatusUseCase,
    private val userPreferences: UserPreferencesRepository,
    private val activeRecipient: ActiveRecipientStore,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(MedicationReconcileState())
    internal val state: StateFlow<MedicationReconcileState> = _state.asStateFlow()

    private var capturedRecipientId: Long = ActiveRecipientStore.NO_RECIPIENT

    init {
        load()
    }

    /** 打开页面：捕获当前成员并按当前态构建全部行（含已停用）。 */
    fun load() {
        viewModelScope.launch {
            capturedRecipientId = activeRecipient.current()
            runCatching { buildLines() }
                .onSuccess { lines ->
                    _state.value = MedicationReconcileState(loading = false, lines = lines)
                }
                .onFailure { _state.update { it.copy(loading = false, failed = true) } }
        }
    }

    fun onToggle(id: Long) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.id == id) it.copy(checked = !it.checked) else it })
        }
    }

    fun onReasonChange(value: String) {
        _state.update { it.copy(reason = value) }
    }

    /** 「核对下一步」：按**确认时点**的实时活状态重算摘要（§2 D7）。 */
    fun toSummary() {
        val intent = _state.value.lines.associate { it.id to it.checked }
        viewModelScope.launch {
            runCatching { buildLines(intent) to today() }
                .onSuccess { (lines, day) ->
                    _state.update {
                        it.copy(
                            step = ReconcileStep.SUMMARY,
                            lines = lines,
                            summary = summarize(lines, day),
                            expectedKinds = expectedOf(lines),
                            stale = false,
                            failed = false,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(failed = true) } }
        }
    }

    /** 「返回修改」：不写任何东西。 */
    fun backToEdit() {
        _state.update {
            it.copy(step = ReconcileStep.LIST, summary = null, stale = false, memberChanged = false, failed = false)
        }
    }

    /** 「确认核对」：单事务提交（含事务内校验）；并发 / 跨零点中止 ⇒ 重建摘要（§7 E15 / §6）。 */
    fun confirm() {
        val snapshot = _state.value
        val summary = snapshot.summary ?: return
        viewModelScope.launch {
            runCatching {
                setStatus.applyReconcile(
                    toPause = summary.toPause,
                    toResume = summary.toResume,
                    date = summary.effectiveDay,
                    reason = snapshot.reason.ifBlank { null },
                    expectedKindByMedication = snapshot.expectedKinds,
                    expectedRecipientId = capturedRecipientId,
                )
            }
                .onSuccess { results ->
                    val recorded = results.filterIsInstance<MedicationStatusWriteResult.EventRecorded>()
                    val resumed = recorded.count { it.kind == MedicationStatusKind.RESUME }
                    val paused = recorded.count { it.kind != MedicationStatusKind.RESUME }
                    _state.update {
                        it.copy(
                            step = ReconcileStep.RESULT,
                            result = ReconcileResult(paused, resumed, it.lines.size - recorded.size),
                            failed = false,
                            stale = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is ReconcileAbortedException) {
                        rebuildAfterAbort()
                    } else {
                        _state.update {
                            it.copy(failed = true)
                        }
                    }
                }
        }
    }

    /** 摘要被中止（外部变更 / 跨零点 / 成员切换）后按**当前**状态重建（§3.2 项 4 / §7 E15 / E9）。 */
    private suspend fun rebuildAfterAbort() {
        val currentRecipient = activeRecipient.current()
        if (currentRecipient != capturedRecipientId) {
            capturedRecipientId = currentRecipient
            val lines = runCatching { buildLines() }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    step = ReconcileStep.LIST,
                    lines = lines,
                    summary = null,
                    expectedKinds = emptyMap(),
                    memberChanged = true,
                    stale = false,
                    failed = false,
                )
            }
            return
        }
        val intent = _state.value.lines.associate { it.id to it.checked }
        val lines = runCatching { buildLines(intent) }.getOrDefault(_state.value.lines)
        val day = today()
        _state.update {
            it.copy(
                step = ReconcileStep.SUMMARY,
                lines = lines,
                summary = summarize(lines, day),
                expectedKinds = expectedOf(lines),
                memberChanged = false,
                stale = true,
                failed = false,
            )
        }
    }

    private fun summarize(lines: List<ReconcileLine>, day: LocalDate): ReconcileSummary {
        val d = diff(lines.map { ReconcileRow(it.id, it.currentlyActive, it.checked) })
        val activeCount = lines.count { it.currentlyActive }
        return ReconcileSummary(
            toPause = d.toPause,
            toResume = d.toResume,
            unchangedCount = lines.size - d.changedCount,
            effectiveDay = day,
            needsSecondConfirmation = MedicationReconcileGuard.requiresSecondConfirmation(
                activeCount,
                d.toPause.size,
                d.changedCount,
            ),
        )
    }

    private fun expectedOf(lines: List<ReconcileLine>): Map<Long, String?> = lines.associate { it.id to it.liveKind }

    private suspend fun buildLines(intent: Map<Long, Boolean>? = null): List<ReconcileLine> {
        val recipient = activeRecipient.current()
        if (recipient == ActiveRecipientStore.NO_RECIPIENT) return emptyList()
        val meds: List<Medication> = medications.getAllMedicationsFor(recipient)
        val byMedication = statusRepository.getEventsForRecipientOnce(recipient).groupBy { it.medicationId }
        val now = clock.millis()
        return meds.map { med ->
            val events = byMedication[med.id].orEmpty()
            val active = MedicationStatusDerivation.statusAt(events, now) == MedicationStatus.ACTIVE
            val live = MedicationStatusDerivation.liveStatusKind(events, now)
            ReconcileLine(
                id = med.id,
                name = med.name,
                checked = intent?.get(med.id) ?: active,
                currentlyActive = active,
                liveKind = live,
                legacyUnknown = live == MedicationStatusKind.LEGACY_BASELINE,
            )
        }
    }

    private suspend fun today(): LocalDate {
        val zone: ZoneId = userPreferences.settingsFlow.first().reminderZone(clock.zone)
        return Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
    }
}
