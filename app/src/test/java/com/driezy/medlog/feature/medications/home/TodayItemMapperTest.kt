package com.driezy.medlog.feature.medications.home

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * T7 时间轴映射器/排序/筛选/进度口径的纯 JVM 契约。
 *
 * 关键不变式：零照护事项时合并结果与用药序列**逐项同序**（[mergeTodayItems] 快路径）。
 */
class TodayItemMapperTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowMs: Long = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()

    // ── 映射器 ──────────────────────────────────────────────

    @Test
    fun `medication mapper preserves input order and excludes PRN`() {
        val morning = medication(id = 1L, time = "08:00", slot = 0)
        val evening = medication(id = 1L, time = "20:00", slot = 1)
        val other = medication(id = 2L, time = "12:00", slot = 0)
        val prn = medication(id = 3L, time = "07:00", slot = 0, isPrn = true)

        val items = medicationsToTodayItems(listOf(morning, evening, other, prn))

        assertEquals(
            listOf(morning.doseKey, evening.doseKey, other.doseKey),
            items.map { it.medication!!.doseKey },
        )
        assertTrue(items.all { it.isMedication })
        assertEquals("MEDICATION:1:0:${morning.scheduledAtMs}", items[0].listKey)
        assertEquals(1, items[0].targetId)
        assertEquals(480, items[0].scheduledMinuteOfDay)
    }

    @Test
    fun `care task mapper expands today occurrences and attaches logs`() {
        val task = careTask(id = 5L, times = "08:00,20:00", category = CareTaskCategory.RESPIRATORY)
        val morningMs = Instant.parse("2026-03-10T08:00:00Z").toEpochMilli()
        val done = CareTaskLog(
            id = 7L,
            careTaskId = 5L,
            scheduledTimeMs = morningMs,
            status = CareTaskLogStatus.DONE,
        )

        val items = careTasksToTodayItems(listOf(task), listOf(done), zone, nowMs)

        assertEquals(2, items.size)
        assertEquals(listOf(480, 1200), items.map { it.scheduledMinuteOfDay })
        assertEquals(listOf(TodayItemStatus.TAKEN, TodayItemStatus.PENDING), items.map { it.status })
        assertTrue(items[0].isHandled)
        assertFalse(items[1].isHandled)
        assertTrue(items.all { it.isCareTask })
        assertEquals(5L, items[0].targetId)
        assertEquals(done, items[0].careTask!!.log)
        assertEquals("CARE_TASK:5:0:$morningMs", items[0].listKey)
    }

    @Test
    fun `care task mapper skips archived and as-needed tasks`() {
        val archived = careTask(id = 1L, times = "08:00", archived = true)
        val asNeeded = careTask(id = 2L, times = "08:00", scheduleKind = CareTaskScheduleKind.AS_NEEDED)

        val items = careTasksToTodayItems(listOf(archived, asNeeded), emptyList(), zone, nowMs)

        assertTrue(items.isEmpty())
    }

    // ── 合并排序 ────────────────────────────────────────────

    @Test
    fun `merge without care tasks returns the medication sequence untouched`() {
        val meds = medicationsToTodayItems(
            listOf(medication(id = 1L, time = "20:00", slot = 0), medication(id = 2L, time = "08:00", slot = 0)),
        )

        val merged = mergeTodayItems(meds, emptyList())

        assertSame(meds, merged)
        assertEquals(listOf(1L, 2L), merged.map { it.targetId })
    }

    @Test
    fun `merge with care tasks interleaves mixed items strictly by time`() {
        val medMorning = medicationsToTodayItems(listOf(medication(id = 1L, time = "08:00", slot = 0)))
        val medEvening = medicationsToTodayItems(listOf(medication(id = 2L, time = "20:00", slot = 0)))
        val care = careTasksToTodayItems(
            listOf(careTask(id = 9L, times = "09:00")),
            emptyList(),
            zone,
            nowMs,
        )

        val merged = mergeTodayItems(medMorning + medEvening, care)

        assertEquals(listOf(480, 540, 1200), merged.map { it.scheduledMinuteOfDay })
        assertEquals(
            listOf(TodayTargetType.MEDICATION, TodayTargetType.CARE_TASK, TodayTargetType.MEDICATION),
            merged.map {
                it.targetType
            },
        )
    }

    // ── 分类分组 ────────────────────────────────────────────

    @Test
    fun `category grouping merges medication and care items sharing a category`() {
        val meds = medicationsToTodayItems(
            listOf(medication(id = 1L, time = "08:00", slot = 0, category = CareTaskCategory.RESPIRATORY)),
        )
        val care = careTasksToTodayItems(
            listOf(careTask(id = 9L, times = "09:00", category = CareTaskCategory.RESPIRATORY)),
            emptyList(),
            zone,
            nowMs,
        )

        val groups = groupTodayItemsByCategory(mergeTodayItems(meds, care))

        assertEquals(listOf(CareTaskCategory.RESPIRATORY), groups.map { it.first })
        assertEquals(2, groups.single().second.size)
    }

    // ── 筛选 ────────────────────────────────────────────────

    @Test
    fun `filter matches by target type and care subcategory`() {
        val med = medicationsToTodayItems(listOf(medication(id = 1L, time = "08:00", slot = 0))).single()
        val care = careTasksToTodayItems(
            listOf(careTask(id = 9L, times = "09:00", category = CareTaskCategory.MOBILITY)),
            emptyList(),
            zone,
            nowMs,
        ).single()

        assertTrue(TodayFilter.All.matches(med))
        assertTrue(TodayFilter.All.matches(care))
        assertTrue(TodayFilter.Medication.matches(med))
        assertFalse(TodayFilter.Medication.matches(care))
        assertTrue(TodayFilter.CareTask.matches(care))
        assertFalse(TodayFilter.CareTask.matches(med))
        assertTrue(TodayFilter.CareCategory(CareTaskCategory.MOBILITY).matches(care))
        assertFalse(TodayFilter.CareCategory(CareTaskCategory.MOBILITY).matches(med))
    }

    // ── 进度口径 ────────────────────────────────────────────

    @Test
    fun `overall progress counts medication and care items together`() {
        val medTaken = medication(id = 1L, time = "08:00", slot = 0, taken = true)
        val medPending = medication(id = 2L, time = "12:00", slot = 0)
        val careDone = careTasksToTodayItems(
            listOf(careTask(id = 9L, times = "09:00")),
            listOf(
                CareTaskLog(
                    id = 1L,
                    careTaskId = 9L,
                    scheduledTimeMs = Instant.parse("2026-03-10T09:00:00Z").toEpochMilli(),
                    status = CareTaskLogStatus.DONE,
                ),
            ),
            zone,
            nowMs,
        )

        val state = HomeUiState(
            todayItems = mergeTodayItems(
                medicationsToTodayItems(listOf(medTaken, medPending)),
                careDone,
            ),
        )

        assertEquals(3, state.overallTotal)
        assertEquals(2, state.overallHandled)
    }

    // ── helpers ─────────────────────────────────────────────

    private fun medication(
        id: Long,
        time: String,
        slot: Int,
        category: String = "",
        taken: Boolean = false,
        isPrn: Boolean = false,
    ): MedicationWithStatus {
        val med = Medication(
            id = id,
            name = "Med $id",
            doseUnit = "tablet",
            reminderTimes = time,
            category = category,
            isPRN = isPrn,
        )
        val log = if (taken) {
            MedicationLog(id = id * 10 + slot, medicationId = id, scheduledTimeMs = 0L, status = LogStatus.TAKEN)
        } else {
            null
        }
        return MedicationWithStatus(
            medication = med,
            log = log,
            timeSlotIndex = slot,
            scheduledTime = time,
            scheduledAtMs = Instant.parse("2026-03-10T$time:00Z").toEpochMilli(),
        )
    }

    private fun careTask(
        id: Long,
        times: String,
        category: String = CareTaskCategory.OTHER,
        archived: Boolean = false,
        scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
    ) = CareTask(
        id = id,
        careRecipientId = 1L,
        title = "Task $id",
        category = category,
        scheduleKind = scheduleKind,
        reminderTimes = times,
        startDate = 0L,
        isArchived = archived,
    )
}
