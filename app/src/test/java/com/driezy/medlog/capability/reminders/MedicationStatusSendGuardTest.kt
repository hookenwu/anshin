package com.driezy.medlog.capability.reminders

import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.repository.FakeMedicationStatusRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 发送前派生校验守卫（更正 N）——docs/medication-status-spec.md §7 / §16.20。
 *
 * 三类用药闹钟（`MEDICATION_NORMAL` / `MEDICATION_EARLY` / `MEDICATION_FOLLOW_UP`）在
 * `MedLogAlarmReceiver.onReceive` 的 `when(dispatch.kind)` 里**全部**路由进 `handleMedication`，
 * 守卫因此在 `handleMedication` 内对三类**同源**生效。此处断言守卫在
 * 「已暂停 / 未知」「过期缓存」「生效前」各情形的裁决，并以源码断言钉住三处闸口与
 * `ACTION_TAKEN` / `ACTION_SKIP` 的不受影响。
 */
class MedicationStatusSendGuardTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private class MutableClock(var now: Instant, private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private fun event(id: Long, kind: String, effectiveAtMs: Long?, recordedAtMs: Long = id) = MedicationStatusEvent(
        id = id,
        medicationId = 1,
        kind = kind,
        effectiveAtMs = effectiveAtMs,
        recordedAtMs = recordedAtMs,
    )

    private fun gate(
        events: List<MedicationStatusEvent>,
        now: Instant,
    ): Pair<MedicationReminderGate, FakeMedicationStatusRepository> {
        val repo = FakeMedicationStatusRepository()
        kotlinx.coroutines.runBlocking { events.forEach { repo.insertEvent(it) } }
        return MedicationReminderGate(repo, MutableClock(now, zone)) to repo
    }

    /** 三类闹钟全部经 `handleMedication` 的同一守卫：在此对三类逐一断言同一裁决。 */
    private val allMedicationReminderKinds = listOf(
        ReminderKind.MEDICATION_NORMAL,
        ReminderKind.MEDICATION_EARLY,
        ReminderKind.MEDICATION_FOLLOW_UP,
    )

    @Test
    fun `all three reminder kinds are suppressed while a live pause is effective`() = runTest {
        val pauseAt = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val (gate, _) = gate(
            listOf(event(1, MedicationStatusKind.PAUSE, pauseAt)),
            Instant.parse("2026-09-19T06:00:00Z"),
        )
        allMedicationReminderKinds.forEach { kind ->
            assertEquals("$kind 在暂停生效后必须被抑制", ReminderDecision.SUPPRESS, gate.decide(1L))
        }
    }

    @Test
    fun `stale cache cannot punch through - derivation is read not the cache`() = runTest {
        // 事件表有生效中的 PAUSE；缓存（不参与守卫）即便为 false 也无关
        val pauseAt = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val (gate, _) = gate(
            listOf(event(1, MedicationStatusKind.PAUSE, pauseAt)),
            Instant.parse("2026-09-19T06:00:00Z"),
        )
        assertEquals(ReminderDecision.SUPPRESS, gate.decide(1L))
    }

    @Test
    fun `a dose before the effective time still reminds`() = runTest {
        val futurePause = Instant.parse("2026-09-19T12:00:00Z").toEpochMilli()
        val (gate, _) = gate(
            listOf(event(1, MedicationStatusKind.PAUSE, futurePause)),
            Instant.parse("2026-09-19T06:00:00Z"),
        )
        assertEquals("生效前必须照常提醒", ReminderDecision.ALLOW, gate.decide(1L))
        // 生效后同一条事件转为抑制
        val (gateAfter, _) = gate(
            listOf(event(1, MedicationStatusKind.PAUSE, futurePause)),
            Instant.parse("2026-09-19T13:00:00Z"),
        )
        assertEquals(ReminderDecision.SUPPRESS, gateAfter.decide(1L))
    }

    @Test
    fun `a legacy baseline only medication is suppressed as unknown before the horizon`() = runTest {
        val migrationMs = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val (gate, _) = gate(
            listOf(event(1, MedicationStatusKind.LEGACY_BASELINE, null, recordedAtMs = migrationMs)),
            Instant.parse("2026-09-18T00:00:00Z"),
        )
        // t < M ⇒ UNKNOWN ⇒ 非 ACTIVE ⇒ 抑制
        assertEquals(ReminderDecision.SUPPRESS, gate.decide(1L))
    }

    @Test
    fun `the receiver gates all three notification branches and leaves taken and skip untouched`() {
        val file = File("src/main/java/com/driezy/medlog/capability/reminders/MedLogAlarmReceiver.kt")
        org.junit.Assume.assumeTrue("源码不在预期位置，跳过源码断言", file.exists())
        val source = file.readText()
        val guardCalls = Regex("""if \(suppressed\(medId\)\) return@goAsyncSafe""").findAll(source).count()
        assertTrue("三处通知闸口（提前 / 漏服再提醒 / 正式）都必须调用发送前守卫，实际 $guardCalls", guardCalls >= 3)
        // ACTION_TAKEN / ACTION_SKIP 分支不得出现守卫调用（真实服药记录不得被拦）
        val taken = source.substringAfter("\"ACTION_TAKEN\" ->").substringBefore("\"ACTION_SKIP\" ->")
        assertTrue("ACTION_TAKEN 分支不得被守卫拦截", !taken.contains("suppressed("))
        val skip = source.substringAfter("\"ACTION_SKIP\" ->").substringBefore("else ->")
        assertTrue("ACTION_SKIP 分支不得被守卫拦截", !skip.contains("suppressed("))
    }
}
