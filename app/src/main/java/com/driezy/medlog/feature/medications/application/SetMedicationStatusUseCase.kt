package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusPreferences
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 首期**不接受**未来生效日（docs/medication-status-spec.md D11 / E5）：错误码。 */
class FutureEffectiveDateNotAllowed(message: String = "生效日不得晚于今天") : IllegalArgumentException(message)

/** 用户动作的合法目标 kind（不含 VOID，见 §5.4）。 */
private val USER_ACTIONS = setOf(
    MedicationStatusKind.PAUSE,
    MedicationStatusKind.DISCONTINUE,
    MedicationStatusKind.RESUME,
)

/** 一次状态写入的结果（docs/medication-status-spec.md §3 D6）。 */
sealed interface MedicationStatusWriteResult {
    /** 活状态 kind ≠ 期望 ⇒ **CHANGE**：append 事件 + 直写缓存 + reconcile。 */
    data class EventRecorded(val kind: String, val medicationId: Long) : MedicationStatusWriteResult

    /** 期望 kind == 当前活 kind ⇒ **CHECK**：不写事件、不写缓存、只更新用户核对日期（§8.2）。 */
    data class UserChecked(val medicationId: Long) : MedicationStatusWriteResult
}

/**
 * 药品状态写入（CHANGE / CHECK）——docs/medication-status-spec.md §3 D6、§5.4、§8.2、D11。
 *
 * - **CHANGE**：比较**活状态 kind**（非暂停布尔）⇒ `desired != live` 时 append 事件 + 同事务直写缓存 + reconcile；
 *   例如 `PAUSE→DISCONTINUE` 是**真实变更**（§5.4）。
 * - **CHECK**：`desired == live` ⇒ **不写事件、不写缓存、不 reconcile**，只写 **用户事实** `lastUserCheckedDate`。
 * - **生效日约束**：只接受今天与过去（按成员提醒时区的本地日界）；未来日抛 [FutureEffectiveDateNotAllowed]。
 * - **绝不**触碰 `medication_logs`；CHECK 不是服药证据（§8.2）。
 */
class SetMedicationStatusUseCase @Inject constructor(
    private val medications: MedicationRepository,
    private val statusRepository: MedicationStatusRepository,
    private val preferences: MedicationStatusPreferences,
    private val userPreferences: UserPreferencesRepository,
    private val activeRecipient: ActiveRecipientStore,
    private val transactions: TransactionRunner,
    private val reconcileReminders: ReconcileRemindersUseCase,
    private val clock: Clock,
) {

    suspend fun apply(
        medicationId: Long,
        desiredKind: String,
        effectiveDate: LocalDate,
        reason: String? = null,
    ): MedicationStatusWriteResult = applyBatch(listOf(medicationId), desiredKind, effectiveDate, reason).single()

    /** 用户主动核对医嘱（显式完成）：同 kind ⇒ 只写 `lastUserCheckedDate`（§8.2）。 */
    suspend fun userCheck(
        medicationId: Long,
        desiredKind: String,
        effectiveDate: LocalDate = today(),
    ): MedicationStatusWriteResult =
        applyBatch(listOf(medicationId), desiredKind, effectiveDate, reason = null).single()

    /**
     * 批量状态写入：一次事务内为 N 个药写事件 + 缓存列；任一失败整体回滚（E10）。
     */
    suspend fun applyBatch(
        medicationIds: List<Long>,
        desiredKind: String,
        effectiveDate: LocalDate,
        reason: String? = null,
    ): List<MedicationStatusWriteResult> {
        require(desiredKind in USER_ACTIONS) { "非法目标状态：$desiredKind" }
        val zone = reminderZone()
        if (effectiveDate > today(zone)) throw FutureEffectiveDateNotAllowed()
        val effectiveAtMs = effectiveDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = clock.millis()
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入用药状态" }

        val results = linkedMapOf<Long, MedicationStatusWriteResult>()
        transactions.withTransaction {
            medicationIds.forEach { medicationId ->
                val events = statusRepository.getEventsOnce(medicationId)
                val live = MedicationStatusDerivation.liveStatusKind(events, now)
                if (live == desiredKind) {
                    results[medicationId] = MedicationStatusWriteResult.UserChecked(medicationId)
                    return@forEach
                }
                val event = MedicationStatusEvent(
                    medicationId = medicationId,
                    kind = desiredKind,
                    effectiveAtMs = effectiveAtMs,
                    recordedAtMs = now,
                    reason = reason,
                )
                val newId = statusRepository.insertEvent(event)
                // 同事务直写缓存（不变量：提交后 medications.isArchived == deriveCurrentStatus，§2.A.3）
                val derived = MedicationStatusDerivation.deriveCurrentStatus(events + event.copy(id = newId), now)
                medications.setArchivedCache(medicationId, derived)
                results[medicationId] = MedicationStatusWriteResult.EventRecorded(desiredKind, medicationId)
            }
        }

        if (results.values.any { it is MedicationStatusWriteResult.UserChecked }) {
            // 用户事实：仅用户核对写入；系统自动通道绝不触碰（§8.2/§8.3）。
            preferences.setUserCheckedDate(recipientId, today(zone).toEpochDay())
        }
        results.values.filterIsInstance<MedicationStatusWriteResult.EventRecorded>().forEach {
            reconcileReminders.medication(MedicationId(it.medicationId), ReminderReconcileReason.MEDICATION_CHANGED)
        }
        return medicationIds.map { results.getValue(it) }
    }

    private suspend fun reminderZone(): ZoneId = userPreferences.settingsFlow.first().reminderZone(clock.zone)

    private fun today(zone: ZoneId = clock.zone): LocalDate =
        java.time.Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
}
