package com.driezy.medlog.feature.careevents.reminder

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 缺席型每日提醒的编排与所有规则（docs/tracked-events-spec.md §5 R1/R2/R4/R6/R7/R11/R12/R13/R14）。
 *
 * 覆盖用户点名的每一条：历史变更只重算不即时通知；已提醒日不因重启/重开/改阈值重复；
 * 发送前四要素再校验；跨日/时区/时钟调整；以及「过期后才开启」只在窗口内生效。
 */
class CareEventReminderEngineTest {

    private val utc = ZoneId.of("UTC")
    private val clock = MutableClock(Instant.parse("2026-01-15T10:00:00Z"))
    private val sends = mutableListOf<CareEventNudge>()
    private val today = LocalDate.parse("2026-01-15").toEpochDay()

    private fun engine(state: CareEventReminderStateSource, zone: ZoneId = utc): CareEventReminderEngine =
        CareEventReminderEngine(state, clock, { zone }, { sends += it })

    private fun overdueAnchor(): Long = Instant.parse("2026-01-10T10:00:00Z").toEpochMilli()

    // ── 基础闸门 ──────────────────────────────────────────────────────────

    @Test
    fun `no active recipient is skipped`() = runBlocking {
        val state = FakeCareEventReminderState(recipientId = 0L, anchorMs = overdueAnchor())
        val decision = engine(state).check()
        assertEquals(CareEventReminderDecision.SKIP_NO_RECIPIENT, decision)
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `disabled switch is skipped`() = runBlocking {
        val state = FakeCareEventReminderState(enabled = false, anchorMs = overdueAnchor())
        assertEquals(CareEventReminderDecision.SKIP_DISABLED, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `no anchor is skipped`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = null)
        assertEquals(CareEventReminderDecision.SKIP_NO_ANCHOR, engine(state).check())
    }

    @Test
    fun `below threshold is skipped`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = clock.millis() - 2 * 86_400_000L)
        assertEquals(CareEventReminderDecision.SKIP_BELOW_THRESHOLD, engine(state).check())
    }

    // ── 窗口（best-effort「今天」） ────────────────────────────────────────

    @Test
    fun `before nine in the morning is skipped and at nine it fires`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())

        clock.now = Instant.parse("2026-01-15T08:59:00Z")
        assertEquals(CareEventReminderDecision.SKIP_BEFORE_WINDOW, engine(state).check())
        assertTrue(sends.isEmpty())

        clock.now = Instant.parse("2026-01-15T09:00:00Z")
        assertEquals(CareEventReminderDecision.NUDGE, engine(state).check())
        assertEquals(1, sends.size)
    }

    @Test
    fun `a late run at 14-30 with the day unmuted still fires`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        clock.now = Instant.parse("2026-01-15T14:30:00Z")
        assertEquals(CareEventReminderDecision.NUDGE, engine(state).check())
        assertEquals(1, sends.size)
    }

    @Test
    fun `a send marks the device-local epoch day`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        engine(state).check()
        assertEquals(listOf(today), state.markedDays)
    }

    // ── 当日去重：重启 / 重开 / 改阈值 ────────────────────────────────────

    @Test
    fun `restart on the same day does not re-nudge`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        assertEquals(CareEventReminderDecision.NUDGE, engine(state).check())
        // 进程被杀重启：状态从持久层重建，日标记已置位。
        assertEquals(CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY, engine(state).check())
        assertEquals(1, sends.size)
    }

    @Test
    fun `turning the switch off and back on the same day does not re-nudge`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        engine(state).check()

        state.enabled = false
        assertEquals(CareEventReminderDecision.SKIP_DISABLED, engine(state).check())
        state.enabled = true
        assertEquals(CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY, engine(state).check())
        assertEquals(1, sends.size)
    }

    @Test
    fun `changing the threshold the same day does not re-nudge`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        engine(state).check()

        state.threshold = 1
        assertEquals(CareEventReminderDecision.SKIP_ALREADY_NUDGED_TODAY, engine(state).check())
        assertEquals(1, sends.size)
    }

    @Test
    fun `day rollover nudges again while still overdue`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor(), nudged = today - 1)
        assertEquals(CareEventReminderDecision.NUDGE, engine(state).check())
        assertEquals(listOf(today), state.markedDays)
    }

    // ── 发送前四要素再校验（R12） ────────────────────────────────────────

    @Test
    fun `revalidation aborts when the active member changed`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        state.mutateBeforeRevalidation = { state.recipientId = 2L }
        assertEquals(CareEventReminderDecision.SKIP_REVALIDATION_FAILED, engine(state).check())
        assertTrue("成员切换后不得发送", sends.isEmpty())
        assertTrue(state.markedDays.isEmpty())
    }

    @Test
    fun `revalidation aborts when the toggle was turned off`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        state.mutateBeforeRevalidation = { state.enabled = false }
        assertEquals(CareEventReminderDecision.SKIP_REVALIDATION_FAILED, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `revalidation aborts when the newest entry changed`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        state.mutateBeforeRevalidation = { state.anchorMs = (state.anchorMs ?: 0L) + 1_000L }
        assertEquals(CareEventReminderDecision.SKIP_REVALIDATION_FAILED, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `revalidation aborts when the threshold changed`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        state.mutateBeforeRevalidation = { state.threshold = 30 }
        assertEquals(CareEventReminderDecision.SKIP_REVALIDATION_FAILED, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    // ── 历史变更只重算、绝不即时通知（R11） ──────────────────────────────

    @Test
    fun `a back-dated history change recomputes but never notifies immediately`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        // 模拟补记一条更晚（但仍在阈值内）的记录：只改状态，无任何 check。
        state.anchorMs = clock.millis() - 3_600_000L
        assertTrue("历史变更本身不得发出任何通知", sends.isEmpty())
        // 下一次被触发的判定：按新锚点重算 → 未超期、不提醒。
        assertEquals(CareEventReminderDecision.SKIP_BELOW_THRESHOLD, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `deleting the newest entry back to none removes eligibility`() = runBlocking {
        val state = FakeCareEventReminderState(anchorMs = overdueAnchor())
        state.anchorMs = null
        assertEquals(CareEventReminderDecision.SKIP_NO_ANCHOR, engine(state).check())
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `a clock adjustment only takes effect at the next in-window check`() = runBlocking {
        // 时钟前拨跨过阈值：时间未到 09:00 时不提醒，到窗口后才提醒。
        val state = FakeCareEventReminderState(anchorMs = Instant.parse("2026-01-11T10:00:00Z").toEpochMilli())
        clock.now = Instant.parse("2026-01-15T07:00:00Z")
        assertEquals(CareEventReminderDecision.SKIP_BEFORE_WINDOW, engine(state).check())
        clock.now = Instant.parse("2026-01-15T10:00:00Z")
        assertEquals(CareEventReminderDecision.NUDGE, engine(state).check())
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
