package com.driezy.medlog.feature.medications.application

import android.util.Log
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

/**
 * 事务内校验失败：摘要所见状态 / 成员 / 日期与提交时不符（docs/medication-daily-reconcile.md §7 E15 / §6）。
 * 整体回滚、**不**落任何事件与缓存、**不**写核对日期；UI 丢弃过期摘要并按当前状态重建。
 */
class ReconcileAbortedException(message: String) : IllegalStateException(message)

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

/** 核对日期写入策略（docs/medication-daily-reconcile.md §4.3）。 */
enum class CheckedDatePolicy {
    /** 仅当结果含 [MedicationStatusWriteResult.UserChecked] 时写（现状单 kind 语义）。 */
    ON_CHECK_ONLY,

    /** 无论如何都写（核对流程：不变更的确认也记录「今天已核对」）。 */
    ALWAYS,
}

/**
 * 药品状态写入（CHANGE / CHECK）——docs/medication-status-spec.md §3 D6、§5.4、§8.2、D11。
 *
 * 唯一实现 [applyStatuses]：N 药 × per-med kind（可混合）在**一个 Room 事务**内；核对日期（DataStore）
 * 与 reconcile 都在**提交之后**、且**彼此独立**（docs/medication-daily-reconcile.md §4.3）。
 * `applyBatch` / `apply` / `userCheck` 都是它的薄包装（对外语义**不变**）。
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
     * 薄包装：转调唯一实现 [applyStatuses]（对外签名与语义**不变**）。
     */
    suspend fun applyBatch(
        medicationIds: List<Long>,
        desiredKind: String,
        effectiveDate: LocalDate,
        reason: String? = null,
    ): List<MedicationStatusWriteResult> =
        applyStatuses(medicationIds.associateWith { desiredKind }, effectiveDate, reason)

    /**
     * 核对流程入口（混合 kind）：停用组 + 恢复组在**同一 Room 事务**内；
     * 事务内校验覆盖 `expectedKindByMedication` 的**每一味**（含未变更者）；
     * 核对日期用**事务验证过的** `summaryDay` 在提交后写（docs/medication-daily-reconcile.md §4.3）。
     */
    suspend fun applyReconcile(
        toPause: List<Long>,
        toResume: List<Long>,
        date: LocalDate,
        reason: String?,
        expectedKindByMedication: Map<Long, String?> = emptyMap(),
        expectedRecipientId: Long? = null,
    ): List<MedicationStatusWriteResult> = applyStatuses(
        desiredKindByMedication = LinkedHashMap<Long, String>().apply {
            toPause.forEach { put(it, MedicationStatusKind.PAUSE) }
            toResume.forEach { put(it, MedicationStatusKind.RESUME) }
        },
        effectiveDate = date,
        reason = reason,
        expectedKindByMedication = expectedKindByMedication,
        checkedDatePolicy = CheckedDatePolicy.ALWAYS,
        summaryDay = date,
        expectedRecipientId = expectedRecipientId,
    )

    /** 无变化的显式登记（D5）：零状态事件，仅写「今天已核对」。 */
    suspend fun markUserChecked(): List<MedicationStatusWriteResult> =
        applyStatuses(emptyMap(), today(), null, emptyMap(), CheckedDatePolicy.ALWAYS)

    /**
     * **唯一**状态写入实现（docs/medication-daily-reconcile.md §4.3）。
     *
     * 事务边界 = {① 事务内校验（含成员 / 跨零点）} ∪ {② 逐药事件插入} ∪ {② 对应缓存直写}；
     * **核对日期（③）与 reconcile（④）在提交之后、彼此独立处理失败**。
     *
     * @param desiredKindByMedication 有序 药 id → 目标 kind（可混合 PAUSE/RESUME/DISCONTINUE）
     * @param expectedKindByMedication 摘要所见：**摘要里显示的每一味**药 id → 活状态 kind；空 = 不做校验
     * @param checkedDatePolicy 核对日期写入策略（默认 = 现状 ON_CHECK_ONLY）
     * @param summaryDay 摘要所见日；非空时在事务内校验「今天未跨日」，且提交后写核对日期只用它
     * @param expectedRecipientId 摘要构建时的活动成员；非空时在事务内比对该成员未切换
     */
    @Suppress("LongParameterList")
    suspend fun applyStatuses(
        desiredKindByMedication: Map<Long, String>,
        effectiveDate: LocalDate,
        reason: String? = null,
        expectedKindByMedication: Map<Long, String?> = emptyMap(),
        checkedDatePolicy: CheckedDatePolicy = CheckedDatePolicy.ON_CHECK_ONLY,
        summaryDay: LocalDate? = null,
        expectedRecipientId: Long? = null,
    ): List<MedicationStatusWriteResult> {
        desiredKindByMedication.values.forEach { require(it in USER_ACTIONS) { "非法目标状态：$it" } }
        val zone = reminderZone()
        if (effectiveDate > today(zone)) throw FutureEffectiveDateNotAllowed()
        val effectiveAtMs = effectiveDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = clock.millis()
        val recipientId = activeRecipient.current()
        check(recipientId != ActiveRecipientStore.NO_RECIPIENT) { "尚未选择家庭成员，禁止写入用药状态" }

        val results = linkedMapOf<Long, MedicationStatusWriteResult>()
        transactions.withTransaction {
            // ① 事务内校验（仅核对路径：expectedKindByMedication 非空）。
            //    覆盖摘要里显示的**每一味药**（含未变更者）：任一不符整体回滚（§7 E15 / D13）。
            if (expectedKindByMedication.isNotEmpty()) {
                if (expectedRecipientId != null && recipientId != expectedRecipientId) {
                    throw ReconcileAbortedException("已切换家庭成员，请重新核对")
                }
                if (summaryDay != null && today(zone) != summaryDay) {
                    throw ReconcileAbortedException("已跨天，请按新日期重新核对")
                }
                expectedKindByMedication.forEach { (medicationId, expected) ->
                    val live = MedicationStatusDerivation.liveStatusKind(
                        statusRepository.getEventsOnce(medicationId),
                        now,
                    )
                    if (live != expected) throw ReconcileAbortedException("药单已变化，请重新确认")
                }
            }
            // ② 逐药（仅变更集）：同 kind ⇒ CHECK；否则 append 事件 + 同事务直写缓存
            desiredKindByMedication.forEach { (medicationId, desiredKind) ->
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

        // ③ 核对日期（post-commit，DataStore）——用事务已验证的 summaryDay，**不**重读 today()。
        //    与 ④ **独立**：本步失败只记日志，绝不阻断 reconcile（docs/medication-daily-reconcile.md §4.3 / A4）。
        val shouldMark = checkedDatePolicy == CheckedDatePolicy.ALWAYS ||
            results.values.any { it is MedicationStatusWriteResult.UserChecked }
        if (shouldMark) {
            val checkedDay = summaryDay ?: today(zone)
            runCatching { preferences.setUserCheckedDate(recipientId, checkedDay.toEpochDay()) }
                .onFailure { Log.w(TAG, "登记用户核对日期失败（弱事实滞后，不影响状态与提醒）", it) }
        }

        // ④ reconcile（post-commit）——每个 EventRecorded 各自独立失败处理。
        results.values.filterIsInstance<MedicationStatusWriteResult.EventRecorded>().forEach {
            runCatching {
                reconcileReminders.medication(MedicationId(it.medicationId), ReminderReconcileReason.MEDICATION_CHANGED)
            }.onFailure { e -> Log.w(TAG, "提醒重排失败（不影响已提交状态与核对日期）", e) }
        }

        return desiredKindByMedication.keys.map { results.getValue(it) }
    }

    private suspend fun reminderZone(): ZoneId = userPreferences.settingsFlow.first().reminderZone(clock.zone)

    private fun today(zone: ZoneId = clock.zone): LocalDate =
        java.time.Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()

    private companion object {
        const val TAG = "SetMedicationStatus"
    }
}
