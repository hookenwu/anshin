package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 投影 / 依从率 dose 级谓词一致（更正 J）——docs/medication-status-spec.md §9.7。
 *
 * 二者都由**同一个** `MedicationStatusDerivation.doseActiveAt` 驱动，故「投影在 `t` 应服的 occurrence 集」
 * 恒等于「依从率在 `t` 记入分母的 occurrence 集」。
 */
class MedicationStatusDoseParityTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone) // 14:00 本地
    private val today = LocalDate.of(2026, 9, 19)
    private val plans = FuturePlanCalculator(clock)
    private val calculator = MedicationAdherenceCalculator(plans)

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun medication(reminderTimes: String = "08:00,20:00") = Medication(
        id = 1,
        careRecipientId = 1,
        name = "药",
        doseUnit = "片",
        startDate = at(today.minusDays(30), 0),
        reminderTimes = reminderTimes,
    )

    private fun event(id: Long, kind: String, effectiveAtMs: Long, recordedAtMs: Long = id) = MedicationStatusEvent(
        id = id,
        medicationId = 1,
        kind = kind,
        effectiveAtMs = effectiveAtMs,
        recordedAtMs = recordedAtMs,
    )

    @Test
    fun `pausing after the morning dose excludes exactly the evening dose in both surfaces`() {
        val med = medication()
        // PAUSE@12:00 当日生效 ⇒ 08:00 应服、20:00 被排除
        val events = listOf(event(1, MedicationStatusKind.PAUSE, at(today, 12)))

        val projected = plans.calculate(
            listOf(med),
            days = 1,
            from = today.atStartOfDay(zone).toInstant(),
            zoneId = zone,
            statusEvents = events,
        ).filter { it.day == today }.map { it.scheduledAt.toEpochMilli() }

        val adherence = calculator.calculate(listOf(med), emptyList(), emptyList(), clock.instant(), zone, events)
        val counted = adherence.days.getValue(today).expectedLogs.map { it.scheduledTimeMs }

        assertEquals("投影当日应服 = {08:00}", listOf(at(today, 8)), projected)
        assertEquals("依从率当日计入分母 = {08:00}", listOf(at(today, 8)), counted)
        assertEquals("归一剂量级排除 = 1 剂（非整日 0、非 2）", 1, adherence.pausedExcluded30d)
        assertEquals(0, adherence.unknownExcluded30d)
    }

    @Test
    fun `resuming at noon excludes the morning dose in both surfaces`() {
        val med = medication()
        val events = listOf(
            event(1, MedicationStatusKind.PAUSE, at(today.minusDays(5), 0), recordedAtMs = 1),
            event(2, MedicationStatusKind.RESUME, at(today, 12), recordedAtMs = 2),
        )

        val projected = plans.calculate(
            listOf(med),
            days = 1,
            from = today.atStartOfDay(zone).toInstant(),
            zoneId = zone,
            statusEvents = events,
        ).filter { it.day == today }.map { it.scheduledAt.toEpochMilli() }

        val adherence = calculator.calculate(listOf(med), emptyList(), emptyList(), clock.instant(), zone, events)
        val counted = adherence.days.getValue(today).expectedLogs.map { it.scheduledTimeMs }

        assertEquals(listOf(at(today, 20)), projected)
        assertEquals(listOf(at(today, 20)), counted)
    }

    @Test
    fun `projection due set equals adherence counted set across randomized plans and events`() {
        val rng = Random(2026)
        repeat(40) { iteration ->
            val med = medication(reminderTimes = "08:00,13:00,20:00").copy(id = iteration.toLong() + 1)
            val events = buildList {
                val count = rng.nextInt(0, 5)
                for (i in 1..count) {
                    add(
                        MedicationStatusEvent(
                            id = i.toLong(),
                            medicationId = med.id,
                            kind = if (rng.nextBoolean()) MedicationStatusKind.PAUSE else MedicationStatusKind.RESUME,
                            effectiveAtMs = at(today.minusDays(rng.nextLong(0, 10)), rng.nextInt(0, 24)),
                            recordedAtMs = i.toLong(),
                        ),
                    )
                }
            }
            val start = today.minusDays(89).atStartOfDay(zone).toInstant()
            val projectedByDay = plans.calculate(
                listOf(med),
                days = 90,
                from = start,
                zoneId = zone,
                statusEvents = events,
            ).groupBy { it.day }.mapValues { entry -> entry.value.map { it.scheduledAt.toEpochMilli() }.toSet() }

            val adherence = calculator.calculate(listOf(med), emptyList(), emptyList(), clock.instant(), zone, events)
            adherence.days.forEach { (date, day) ->
                assertEquals(
                    "投影与依从率在 $date 必须逐 occurrence 一致",
                    projectedByDay[date].orEmpty(),
                    day.expectedLogs.map { it.scheduledTimeMs }.toSet(),
                )
            }
        }
    }
}
