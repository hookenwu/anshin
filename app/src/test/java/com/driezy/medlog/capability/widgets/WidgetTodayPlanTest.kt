package com.driezy.medlog.capability.widgets

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.repository.FakeLogRepository
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.LogRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.medications.application.ToggleMedicationDoseUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * `todayPlan()` 是 NextDoseWidget / MedLogWidget 共用的唯一数据入口。
 *
 * 归档动作会在 `medication_plan_revisions` 里留下一份 `isArchived=false` 的旧计划快照
 * （设备实测 med 20-23 各有一条，窗口覆盖归档当天）。该快照的 `isArchived` 是拷贝来的
 * `false`，所以 `FuturePlanCalculator` 只跳过"当前已归档"的守卫对它无效，归档药会在
 * 归档当天被重新投影成"下一剂"，打卡按钮可用且 taken/total 分母被撑大。
 * 栅栏收在 [WidgetUtils.todayPlan] 边界（与首页 `HomeViewModel` 同一口径）。
 */
class WidgetTodayPlanTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-19T01:00:00Z") // 09:00 上海
    private val clock: Clock = Clock.fixed(now, zone)
    private val meds = FakeMedicationRepository()
    private val logs = FakeLogRepository()

    private val todayStartMs = LocalDate.of(2026, 9, 19).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entryPoint(): WidgetEntryPoint {
        val prefs = mock<UserPreferencesRepository> { on { settingsFlow } doReturn flowOf(SettingsPreferences()) }
        return object : WidgetEntryPoint {
            override fun toggleMedicationDoseUseCase(): ToggleMedicationDoseUseCase = error("todayPlan 不读取该用例")
            override fun medicationRepository(): MedicationRepository = meds
            override fun logRepository(): LogRepository = logs
            override fun preferences(): UserPreferencesRepository = prefs
            override fun clock(): Clock = clock
        }
    }

    @Test
    fun `todayPlan excludes an archived medication projected by a stale revision`() = runTest {
        val liveId = meds.addMedication(
            Medication(name = "在服", doseUnit = "片", reminderTimes = "20:00", startDate = todayStartMs),
        )
        val archivedId = meds.addMedication(
            Medication(name = "停用", doseUnit = "片", reminderTimes = "08:00,20:00", startDate = todayStartMs),
        )
        meds.archiveMedication(archivedId)
        // 归档留下的旧快照：isArchived=false，窗口覆盖今日 → 未加栅栏时会被投影出 08:00 与 20:00 两剂。
        meds.planRevisions.value = listOf(
            revision(
                medicationId = archivedId,
                effectiveUntilMs = now.toEpochMilli() + Duration.ofDays(1).toMillis(),
                reminderTimes = "08:00,20:00",
            ),
        )

        val plan = entryPoint().todayPlan()

        assertTrue(
            "归档药品不得出现在待办剂量里（残留版本快照 isArchived=false 也不得豁免）",
            plan.pending.none { it.first == archivedId },
        )
        assertEquals("taken/total 分母只应含在用药", 1, plan.total)
        assertEquals(listOf(liveId), plan.pending.map { it.first })
    }

    @Test
    fun `todayPlan keeps a live medication that has a plan revision`() = runTest {
        // 活药的旧版本窗口 [.., 12:00)、当前排期自 12:00 起——两者不重叠，各出一剂。
        val noonMs = todayStartMs + Duration.ofHours(12).toMillis()
        val liveId = meds.addMedication(
            Medication(
                name = "在服",
                doseUnit = "片",
                reminderTimes = "20:00",
                startDate = todayStartMs,
                planEffectiveFromMs = noonMs,
            ),
        )
        meds.planRevisions.value = listOf(
            revision(medicationId = liveId, effectiveUntilMs = noonMs, reminderTimes = "08:00"),
        )

        val plan = entryPoint().todayPlan()

        assertEquals("活药（含版本历史）不得被栅栏误滤", 2, plan.total)
        assertTrue(plan.pending.all { it.first == liveId })
        assertEquals(setOf(8 * 60, 20 * 60), plan.pending.map { it.third }.toSet())
    }

    private fun revision(medicationId: Long, effectiveUntilMs: Long, reminderTimes: String) = MedicationPlanRevision(
        medicationId = medicationId,
        effectiveFromMs = 0L,
        effectiveUntilMs = effectiveUntilMs,
        startDate = 0L,
        endDate = null,
        frequencyType = "daily",
        frequencyInterval = 1,
        frequencyDays = "1,2,3,4,5,6,7",
        timePeriod = "exact",
        reminderTimes = reminderTimes,
        reminderHour = 8,
        reminderMinute = 0,
        intervalHours = 0,
        isPRN = false,
        isArchived = false,
        doseQuantity = 1.0,
        doseUnit = "片",
    )
}
