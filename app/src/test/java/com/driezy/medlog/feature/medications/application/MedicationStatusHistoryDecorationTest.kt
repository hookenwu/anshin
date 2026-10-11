package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 历史 / 日历的暂停区间与「已调整」呈现数据（docs/medication-status-spec.md §6.1 / §6.3 / §9.8）。
 *
 * 逐日装饰（`AdherenceDay.pausedDoses` / `unknownDoses` / `adjusted`）与投影、依从率同源
 * （同一个 `MedicationStatusDerivation.doseActiveAt` 谓词），全部按 **dose 级** 判定。
 */
class MedicationStatusHistoryDecorationTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone) // 14:00 本地
    private val today = LocalDate.of(2026, 9, 19)
    private val plans = FuturePlanCalculator(clock)
    private val calculator = MedicationAdherenceCalculator(plans)

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun medication(reminderTimes: String = "08:00,13:00,20:00") = Medication(
        id = 1,
        careRecipientId = 1,
        name = "药",
        doseUnit = "片",
        startDate = at(today.minusDays(10), 0),
        reminderTimes = reminderTimes,
    )

    private fun event(
        id: Long,
        kind: String,
        effectiveAtMs: Long?,
        recordedAtMs: Long = id,
        supersedesId: Long? = null,
    ) = MedicationStatusEvent(
        id = id,
        medicationId = 1,
        kind = kind,
        effectiveAtMs = effectiveAtMs,
        recordedAtMs = recordedAtMs,
        supersedesId = supersedesId,
    )

    @Test
    fun `a fully paused day is marked paused with no due doses`() {
        val events = listOf(event(1, MedicationStatusKind.PAUSE, at(today.minusDays(2), 0)))

        val adherence = calculator.calculate(
            listOf(medication()),
            emptyList(),
            emptyList(),
            clock.instant(),
            zone,
            events,
        )
        val day = adherence.days.getValue(today.minusDays(1))

        assertEquals("整日 3 剂全部暂停", 3, day.pausedDoses)
        assertEquals(0, day.unknownDoses)
        assertTrue("全部剂次暂停 ⇒ fullyPaused", day.fullyPaused)
        assertFalse(day.partiallyExcluded)
        assertTrue("暂停日不进分母、不合成漏服", day.expectedLogs.isEmpty())
        assertEquals(0, day.total)
    }

    @Test
    fun `a partially paused day keeps the unexcluded doses in the denominator`() {
        // PAUSE@12:00 ⇒ 08:00 / 13:00 仍应服？不：13:00 > 12:00 也被排除 ⇒ 只留 08:00
        val events = listOf(event(1, MedicationStatusKind.PAUSE, at(today, 12)))

        val adherence = calculator.calculate(
            listOf(medication()),
            emptyList(),
            emptyList(),
            clock.instant(),
            zone,
            events,
        )
        val day = adherence.days.getValue(today)

        assertEquals("13:00 / 20:00 被排除 ⇒ 2 剂", 2, day.pausedDoses)
        assertEquals("只留 08:00 ⇒ 分母 1", 1, day.total)
        assertTrue(day.partiallyExcluded)
        assertFalse(day.fullyPaused)
        assertEquals(setOf(at(today, 8)), day.expectedLogs.map { it.scheduledTimeMs }.toSet())
    }

    @Test
    fun `a pre migration window is marked unknown not paused and never missed`() {
        val migrationMs = at(today.minusDays(1), 0)
        val baseline = event(1, MedicationStatusKind.LEGACY_BASELINE, null, recordedAtMs = migrationMs)

        val adherence = calculator.calculate(
            listOf(medication()),
            emptyList(),
            emptyList(),
            clock.instant(),
            zone,
            listOf(baseline),
        )

        val before = adherence.days.getValue(today.minusDays(2))
        assertEquals("迁移前不得标暂停", 0, before.pausedDoses)
        assertTrue("迁移前剂次单列未知", before.unknownDoses > 0)
        assertTrue(before.expectedLogs.isEmpty())
        assertFalse(before.fullyPaused)

        val afterMigration = adherence.days.getValue(today)
        assertEquals("迁移后已证归档 ⇒ 暂停", 3, afterMigration.pausedDoses)
        assertEquals("未知与暂停分列", 0, afterMigration.unknownDoses)
    }

    @Test
    fun `a correction marks only the rewritten days adjusted`() {
        // 原始 PAUSE@today-5（id=1）；更正为 PAUSE@today（id=2，取代 id=1）
        val events = listOf(
            event(1, MedicationStatusKind.PAUSE, at(today.minusDays(5), 0), recordedAtMs = 1),
            event(2, MedicationStatusKind.PAUSE, at(today, 0), recordedAtMs = 2, supersedesId = 1),
        )

        val adherence = calculator.calculate(
            listOf(medication()),
            emptyList(),
            emptyList(),
            clock.instant(),
            zone,
            events,
        )

        val rewritten = adherence.days.getValue(today.minusDays(3))
        assertTrue("被更正改写的日子标记「已调整」", rewritten.adjusted)
        assertEquals("更正后该日为应服（原暂停被撤销）", 0, rewritten.pausedDoses)
        assertEquals("已调整日仍有应服剂次", 3, rewritten.total)

        val untouched = adherence.days.getValue(today.minusDays(6))
        assertFalse("更正未触及的日子不得标记", untouched.adjusted)
    }
}
