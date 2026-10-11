package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 误记更正 = **追加取代事件 + 重算**（docs/medication-status-spec.md §2.C / §9.2 / D10）。
 *
 * 不就地编辑、不物理删除任何行（append-only）。三种误记：
 * - 错生效日 → append 一条 `effectiveAtMs` 正确、`supersedesId=错事件` 的同 kind 事件；
 * - 错 kind → append 正确 kind、`supersedesId=错事件` 的事件；
 * - 重复条目 → append 一条 `VOID` 事件 `supersedesId=重复者`。
 *
 * 更正**一律 append**（无论净 kind 是否改变——审计链要求记下这次更正，§5.4）。
 * 更正后重跑状态层 → 刷新缓存 + reconcile；**绝不**触碰 `medication_logs`。
 */
class CorrectMedicationStatusEventUseCase @Inject constructor(
    private val statusRepository: MedicationStatusRepository,
    private val medications: MedicationRepository,
    private val preferences: UserPreferencesRepository,
    private val transactions: TransactionRunner,
    private val reconcileReminders: ReconcileRemindersUseCase,
    private val clock: Clock,
) {

    /** 更正错生效日 / 错 kind：追加同 kind（或新 kind）事件指向被更正者。返回新事件 id。 */
    suspend fun correct(
        supersededEventId: Long,
        newKind: String,
        newEffectiveDate: LocalDate,
        reason: String? = null,
    ): Long {
        check(newKind in USER_ACTION_KINDS) { "更正目标 kind 非法：$newKind" }
        val zone = reminderZone()
        if (newEffectiveDate > today(zone)) throw FutureEffectiveDateNotAllowed()
        return appendSuperseding(
            supersededEventId = supersededEventId,
            kind = newKind,
            effectiveAtMs = newEffectiveDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            reason = reason,
        )
    }

    /** 作废重复条目：追加一条 `VOID`（生效日 NULL）指向重复者。返回新事件 id。 */
    suspend fun void(supersededEventId: Long, reason: String? = null): Long = appendSuperseding(
        supersededEventId = supersededEventId,
        kind = MedicationStatusKind.VOID,
        effectiveAtMs = null,
        reason = reason,
    )

    private suspend fun appendSuperseding(
        supersededEventId: Long,
        kind: String,
        effectiveAtMs: Long?,
        reason: String?,
    ): Long {
        val target = statusRepository.getEventById(supersededEventId)
            ?: error("被更正事件不存在：$supersededEventId")
        val now = clock.millis()
        val newId = transactions.withTransaction {
            val event = MedicationStatusEvent(
                medicationId = target.medicationId,
                kind = kind,
                effectiveAtMs = effectiveAtMs,
                recordedAtMs = now,
                reason = reason,
                supersedesId = supersededEventId,
            )
            val id = statusRepository.insertEvent(event)
            val events = statusRepository.getEventsOnce(target.medicationId)
            medications.setArchivedCache(
                target.medicationId,
                MedicationStatusDerivation.deriveCurrentStatus(events, now),
            )
            id
        }
        reconcileReminders.medication(MedicationId(target.medicationId), ReminderReconcileReason.MEDICATION_CHANGED)
        return newId
    }

    private suspend fun reminderZone(): ZoneId = preferences.settingsFlow.first().reminderZone(clock.zone)

    private fun today(zone: ZoneId): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()

    private companion object {
        val USER_ACTION_KINDS = setOf(
            MedicationStatusKind.PAUSE,
            MedicationStatusKind.DISCONTINUE,
            MedicationStatusKind.RESUME,
        )
    }
}
