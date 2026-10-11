package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.repository.LogRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusRepository
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.di.ComputationDispatcher
import com.driezy.medlog.domain.StreakCalculator
import com.driezy.medlog.feature.medications.application.matchDoseLogsToSlots
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

const val UNKNOWN_MEDICATION_NAME = "\u0000__unknown__"

data class AdherenceDay(
    val date: LocalDate,
    val taken: Int,
    val partial: Int,
    val total: Int,
    val logs: List<Pair<MedicationLog, String>>,
    val expectedLogs: List<MedicationLog> = logs.map { it.first },
    /** 该日因**暂停**而逐剂排除出分母的剂次数（docs/medication-status-spec.md §6.1/§6.2）。 */
    val pausedDoses: Int = 0,
    /** 该日因「**状态未知（早于本次迁移）**」而逐剂排除的剂次数（§6.1/§6.3，与暂停分列）。 */
    val unknownDoses: Int = 0,
    /** 该日是否受**倒填更正**影响（§6.1「已调整」标记）。 */
    val adjusted: Boolean = false,
) {
    val pending: Int get() = expectedLogs.count { it.status == LogStatus.PENDING }
    val resolved: Int get() = expectedLogs.size - pending

    /** Full doses / due scheduled doses. Partial doses are reported separately, without an invented weight. */
    val rate: Float get() = if (resolved == 0) 0f else taken.toFloat() / resolved

    /** 该日**全部**应服剂次皆被排除为暂停 ⇒ 「暂停（不计漏服）」（§6.1）。 */
    val fullyPaused: Boolean get() = pausedDoses > 0 && unknownDoses == 0 && expectedLogs.isEmpty()

    /** 该日**部分**剂次被排除（暂停或未知）⇒ 「部分暂停（该时点不计漏服）」（§6.1）。 */
    val partiallyExcluded: Boolean get() = (pausedDoses > 0 || unknownDoses > 0) && !fullyPaused
}

data class MedicationAdherence(
    val today: LocalDate,
    val zone: ZoneId,
    val medications: List<Medication>,
    val logs: List<MedicationLog>,
    val days: Map<LocalDate, AdherenceDay>,
    /** 近 30 天被暂停而**逐剂**排除出分母的剂次数（docs/medication-status-spec.md §6.2）。 */
    val pausedExcluded30d: Int = 0,
    /** 近 30 天因「状态未知（早于本次迁移）」而逐剂排除的剂次数（§6.3；与暂停分列，绝不合成 MISSED）。 */
    val unknownExcluded30d: Int = 0,
) {
    private val recentDays by lazy { days.filterKeys { it >= today.minusDays(29) }.values }
    val taken30d by lazy { recentDays.sumOf { it.taken } }
    val partial30d by lazy { recentDays.sumOf { it.partial } }
    val total30d by lazy { recentDays.sumOf { it.resolved } }
    val rate30d get() = if (total30d == 0) 0f else taken30d.toFloat() / total30d
    private val recordedDates by lazy {
        logs.filter { it.status == LogStatus.TAKEN || it.status == LogStatus.PARTIAL }
            .map { Instant.ofEpochMilli(it.scheduledTimeMs).atZone(zone).toLocalDate() }.toSet()
    }
    val currentStreak get() = StreakCalculator.currentStreak(recordedDates, today)
    val longestStreak get() = StreakCalculator.longestStreak(recordedDates)
}

class MedicationAdherenceCalculator @Inject constructor(private val plans: FuturePlanCalculator) {
    fun calculate(
        medications: List<Medication>,
        logs: List<MedicationLog>,
        revisions: List<MedicationPlanRevision>,
        now: Instant,
        zone: ZoneId,
        statusEvents: List<MedicationStatusEvent> = emptyList(),
    ): MedicationAdherence {
        val today = now.atZone(zone).toLocalDate()
        val firstDate = today.minusDays(89)
        val eventsById = statusEvents.groupBy { it.medicationId }
        val included = medications.filter {
            !it.isArchived || it.endDate != null || revisions.any { r -> r.medicationId == it.id }
        }
        val planned = plans.calculate(
            included,
            days = 90,
            from = firstDate.atStartOfDay(zone).toInstant(),
            zoneId = zone,
            includeArchived = true,
            revisions = revisions,
            logs = logs,
            // 与投影同一 dose 级谓词（更正 J）：暂停 / 未知的剂次不进 expected、不进分母
            statusEvents = statusEvents,
        ).groupBy { it.day }
        val byId = medications.associateBy { it.id }
        val byDay = logs.groupBy { Instant.ofEpochMilli(it.scheduledTimeMs).atZone(zone).toLocalDate() }
        // 逐日暂停 / 未知 / 已调整装饰（§6.1）：与投影同一 dose 级谓词，仅用于呈现，不影响分母。
        val decoration = decorationByDay(included, revisions, logs, eventsById, firstDate, zone)
        val exclusions = excludedCounts(medications, revisions, logs, eventsById, firstDate, today, zone)
        val days = (planned.keys + byDay.keys + decoration.keys + today)
            .filter { it in firstDate..today }.associateWith { date ->
                val actual = byDay[date].orEmpty()
                val matched = mutableSetOf<MedicationLog>()
                val expected = planned[date].orEmpty().groupBy { it.medication.id }.flatMap { (id, slots) ->
                    val matches =
                        matchDoseLogsToSlots(
                            slots.map { it.scheduledAt.toEpochMilli() },
                            actual.filter {
                                it.medicationId ==
                                    id
                            },
                        )
                    slots.mapIndexed { i, slot ->
                        matches[i]?.also { matched += it } ?: MedicationLog(
                            id = -slot.scheduledAt.toEpochMilli(),
                            medicationId = id,
                            scheduledTimeMs = slot.scheduledAt.toEpochMilli(),
                            status = if (slot.scheduledAt > now) LogStatus.PENDING else LogStatus.MISSED,
                            createdAtMs = 0L,
                        )
                    }
                }
                val extra = actual.filterNot { it in matched }
                val counted = expected + extra.filter { byId[it.medicationId]?.isPRN != true }
                val all = (expected + extra).sortedBy { it.scheduledTimeMs }
                AdherenceDay(
                    date,
                    counted.count { it.status == LogStatus.TAKEN },
                    counted.count { it.status == LogStatus.PARTIAL },
                    counted.size,
                    all.map { it to (byId[it.medicationId]?.name ?: UNKNOWN_MEDICATION_NAME) },
                    counted,
                    pausedDoses = decoration[date]?.paused ?: 0,
                    unknownDoses = decoration[date]?.unknown ?: 0,
                    adjusted = decoration[date]?.adjusted ?: false,
                )
            }
        return MedicationAdherence(
            today,
            zone,
            medications,
            logs,
            days,
            pausedExcluded30d = exclusions.paused,
            unknownExcluded30d = exclusions.unknown,
        )
    }

    /** 逐剂排除计数的近 30 天窗口汇总（暂停 / 未知**分列**，§6.2/§6.3）。 */
    private data class ExclusionCounts(val paused: Int, val unknown: Int)

    /**
     * 逐剂排除计数（§6.2/§6.3）：对**完整计划**（不过滤状态）的每个 occurrence，按其在
     * `scheduledAt` 的派生状态分类。仅统计近 30 天窗口；暂停与未知**分列**，绝不合成 MISSED。
     */
    private fun excludedCounts(
        medications: List<Medication>,
        revisions: List<MedicationPlanRevision>,
        logs: List<MedicationLog>,
        eventsById: Map<Long, List<MedicationStatusEvent>>,
        firstDate: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): ExclusionCounts {
        val windowStart = today.minusDays(29)
        if (windowStart > today) return ExclusionCounts(0, 0)
        var paused = 0
        var unknown = 0
        plans.calculate(
            medications,
            days = 90,
            from = firstDate.atStartOfDay(zone).toInstant(),
            zoneId = zone,
            includeArchived = true,
            revisions = revisions,
            logs = logs,
            statusEvents = emptyList(),
        ).forEach { item ->
            if (item.day !in windowStart..today) return@forEach
            when (
                MedicationStatusDerivation.statusAt(
                    eventsById[item.medication.id].orEmpty(),
                    item.scheduledAt.toEpochMilli(),
                )
            ) {
                MedicationStatus.PAUSED -> paused++
                MedicationStatus.UNKNOWN -> unknown++
                MedicationStatus.ACTIVE -> Unit
            }
        }
        return ExclusionCounts(paused, unknown)
    }

    /** 单日的暂停 / 未知剂次与「已调整」标记（§6.1 历史/日历呈现，全部按 dose 级判定）。 */
    private data class DayDecoration(var paused: Int = 0, var unknown: Int = 0, var adjusted: Boolean = false)

    /**
     * 逐日装饰（§6.1）：对**完整计划**的每个 occurrence，用与投影/依从率**同一个** dose 级谓词
     * 判定其在 `scheduledAt` 的状态，按日聚合暂停 / 未知剂次数；「已调整」= 去掉更正事件
     * （`supersedesId != null`）后该时点的派生状态改变，即该日因**更正**被改写。
     */
    private fun decorationByDay(
        medications: List<Medication>,
        revisions: List<MedicationPlanRevision>,
        logs: List<MedicationLog>,
        eventsById: Map<Long, List<MedicationStatusEvent>>,
        firstDate: LocalDate,
        zone: ZoneId,
    ): Map<LocalDate, DayDecoration> {
        val result = mutableMapOf<LocalDate, DayDecoration>()
        plans.calculate(
            medications,
            days = 90,
            from = firstDate.atStartOfDay(zone).toInstant(),
            zoneId = zone,
            includeArchived = true,
            revisions = revisions,
            logs = logs,
            statusEvents = emptyList(),
        ).forEach { item ->
            val events = eventsById[item.medication.id].orEmpty()
            val instant = item.scheduledAt.toEpochMilli()
            val status = MedicationStatusDerivation.statusAt(events, instant)
            val decoration = result.getOrPut(item.day) { DayDecoration() }
            when (status) {
                MedicationStatus.PAUSED -> decoration.paused++
                MedicationStatus.UNKNOWN -> decoration.unknown++
                MedicationStatus.ACTIVE -> Unit
            }
            val withoutCorrections = events.filter { it.supersedesId == null }
            if (MedicationStatusDerivation.statusAt(withoutCorrections, instant) != status) {
                decoration.adjusted = true
            }
        }
        return result
    }
}

/** One reactive, timezone-aware query shared by history and medication details. */
class ObserveMedicationAdherence @Inject constructor(
    private val medications: MedicationRepository,
    private val logs: LogRepository,
    private val preferences: UserPreferencesRepository,
    private val statusRepository: MedicationStatusRepository,
    private val calculator: MedicationAdherenceCalculator,
    private val clock: Clock,
    @param:ComputationDispatcher private val dispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(time: Flow<Instant>, medicationId: Long? = null): Flow<MedicationAdherence> = combine(
        time,
        preferences.settingsFlow.map {
            it.reminderZone(clock.zone)
        }.distinctUntilChanged(),
    ) { now, zone ->
        now.atZone(zone).toLocalDate() to zone
    }.distinctUntilChanged().flatMapLatest { (today, zone) ->
        combine(
            medications.getAllMedications(),
            medications.observePlanRevisions(),
            logs.getLogsForDateRange(0L, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1),
            time,
            statusRepository.observeAllEvents(),
        ) { meds, revisions, records, now, statusEvents ->
            calculator.calculate(
                meds.filter { medicationId == null || it.id == medicationId },
                records.filter { medicationId == null || it.medicationId == medicationId },
                revisions,
                now,
                zone,
                statusEvents.filter { medicationId == null || it.medicationId == medicationId },
            )
        }
    }.flowOn(dispatcher)
}
