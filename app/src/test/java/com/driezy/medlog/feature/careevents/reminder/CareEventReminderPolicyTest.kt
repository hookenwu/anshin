package com.driezy.medlog.feature.careevents.reminder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 缺席型每日提醒的纯判定（docs/tracked-events-spec.md §5 R1/R2/R4/R6/R13/R14）。
 */
class CareEventReminderPolicyTest {

    private val utc = ZoneId.of("UTC")
    private val anchor = Instant.parse("2026-01-10T08:00:00Z").toEpochMilli()
    private val today = LocalDate.parse("2026-01-15").toEpochDay()

    private fun now(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun input(
        enabled: Boolean = true,
        thresholdDays: Int = 3,
        anchorMs: Long? = anchor,
        nudgedDay: Long? = null,
        nowMs: Long = now("2026-01-15T10:00:00Z"),
        zoneId: ZoneId = utc,
    ) = CareEventReminderInput(enabled, thresholdDays, anchorMs, nudgedDay, nowMs, zoneId)

    @Test
    fun `defaults are three days and a nine o'clock window`() {
        assertEquals(3, CareEventReminderPolicy.DEFAULT_THRESHOLD_DAYS)
        assertEquals(9, CareEventReminderPolicy.WINDOW_START_HOUR)
    }

    @Test
    fun `disabled switch never nudges`() {
        assertEquals(CareEventReminderDecision.SKIP_DISABLED, CareEventReminderPolicy.decide(input(enabled = false)))
    }

    @Test
    fun `no anchor never nudges`() {
        assertEquals(CareEventReminderDecision.SKIP_NO_ANCHOR, CareEventReminderPolicy.decide(input(anchorMs = null)))
    }

    @Test
    fun `below threshold never nudges`() {
        val fresh = now("2026-01-15T10:00:00Z") - 2 * 86_400_000L
        assertEquals(
            CareEventReminderDecision.SKIP_BELOW_THRESHOLD,
            CareEventReminderPolicy.decide(input(anchorMs = fresh)),
        )
    }

    @Test
    fun `before nine in the morning never nudges`() {
        assertEquals(
            CareEventReminderDecision.SKIP_BEFORE_WINDOW,
            CareEventReminderPolicy.decide(input(nowMs = now("2026-01-15T08:30:00Z"))),
        )
    }

    @Test
    fun `exactly at nine in the morning does nudge`() {
        assertEquals(
            CareEventReminderDecision.NUDGE,
            CareEventReminderPolicy.decide(input(nowMs = now("2026-01-15T09:00:00Z"))),
        )
    }

    @Test
    fun `a late run at 14-30 with the day unmuted still nudges`() {
        assertEquals(
            "窗口是「今天」而不是 09:00-09:15",
            CareEventReminderDecision.NUDGE,
            CareEventReminderPolicy.decide(input(nowMs = now("2026-01-15T14:30:00Z"))),
        )
    }

    @Test
    fun `already nudged today is skipped`() {
        assertEquals(
            CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY,
            CareEventReminderPolicy.decide(input(nudgedDay = today)),
        )
    }

    @Test
    fun `day rollover makes an overdue item eligible again`() {
        assertEquals(
            CareEventReminderDecision.NUDGE,
            CareEventReminderPolicy.decide(input(nudgedDay = today - 1)),
        )
    }

    @Test
    fun `the day marker is monotonic and never re-sends when the local day goes backwards`() {
        // 时钟回拨 / 时区西移：标记“大于”今天时不得重发。
        assertEquals(
            CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY,
            CareEventReminderPolicy.decide(input(nudgedDay = today + 1)),
        )
    }

    @Test
    fun `a timezone shift to a later local day re-evaluates against the new day`() {
        // 同一时刻：UTC 2026-01-15 20:00，Kiritimati(+14) 本地已是次日 10:00。
        val instant = now("2026-01-15T20:00:00Z")
        val nudge = CareEventReminderPolicy.decide(
            input(nudgedDay = today, nowMs = instant, zoneId = ZoneId.of("Pacific/Kiritimati")),
        )
        assertEquals("新本地日 > 已提醒日 → 可再次提醒", CareEventReminderDecision.NUDGE, nudge)
    }

    @Test
    fun `a timezone shift to an earlier local day does not re-send`() {
        // 同一时刻的另一端：Honolulu(-10) 本地仍是 2026-01-14 19:00，早于已提醒日，不回退重发。
        val instant = now("2026-01-15T05:00:00Z")
        val skipped = CareEventReminderPolicy.decide(
            input(nudgedDay = today, nowMs = instant, zoneId = ZoneId.of("Pacific/Honolulu")),
        )
        assertEquals(CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY, skipped)
    }

    @Test
    fun `local epoch day tracks the zone`() {
        // 同一时刻在东部时区可能已是次日；在西部时区可能仍是前一日。
        val evening = now("2026-01-15T20:00:00Z")
        assertEquals(today + 1, CareEventReminderPolicy.localEpochDay(evening, ZoneId.of("Pacific/Kiritimati")))
        val earlyMorning = now("2026-01-15T05:00:00Z")
        assertEquals(today - 1, CareEventReminderPolicy.localEpochDay(earlyMorning, ZoneId.of("Pacific/Honolulu")))
    }

    @Test
    fun `never nudged sentinel is smaller than any real epoch day`() {
        assertEquals(Long.MIN_VALUE, CareEventReminderPolicy.NEVER_NUDGED)
    }
}
