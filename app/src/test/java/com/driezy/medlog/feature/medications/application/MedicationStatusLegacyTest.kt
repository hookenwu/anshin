package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗留基线（更正 I / G / M）——docs/medication-status-spec.md §9.4 / §9.8 / §16.14 / §16.17。
 *
 * 设迁移时刻为 `M`（= 基线 `recordedAtMs`）。迁移只确知**迁移时刻**已归档：`t >= M` = PAUSED，
 * `t < M` = **UNKNOWN**（既非暂停、也非活跃）。首个 `RESUME@D` **不重解释** `D` 之前的历史。
 *
 * （「迁移补种恰一条基线行」的库级断言在 `core/database` 的 `MedicationStatusMigrationTest`。）
 */
class MedicationStatusLegacyTest {

    private val d = MedicationStatusDerivation
    private val migrationMs = 1_700_000_000_000L

    private fun baseline(id: Long = 1, recordedAtMs: Long = migrationMs) = MedicationStatusEvent(
        id = id,
        medicationId = 1,
        kind = MedicationStatusKind.LEGACY_BASELINE,
        effectiveAtMs = null,
        recordedAtMs = recordedAtMs,
    )

    private fun event(id: Long, kind: String, at: Long, recordedAtMs: Long = id) = MedicationStatusEvent(
        id = id,
        medicationId = 1,
        kind = kind,
        effectiveAtMs = at,
        recordedAtMs = recordedAtMs,
    )

    @Test
    fun `baseline asserts paused at the migration horizon and unknown before it`() {
        val events = listOf(baseline())
        // 迁移时刻起：已证归档（PAUSED）
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, migrationMs))
        assertTrue(d.pausedAt(events, migrationMs))
        // 迁移前：状态未知（既非暂停也非活跃）
        assertEquals(MedicationStatus.UNKNOWN, d.statusAt(events, migrationMs - 1))
        assertTrue(d.unknownAt(events, migrationMs - 1))
        assertFalse(d.pausedAt(events, migrationMs - 1))
        assertFalse(d.doseActiveAt(events, migrationMs - 1))
    }

    @Test
    fun `baseline never fabricates a pause start nor an active past`() {
        val events = listOf(baseline())
        // 迁移前的任意时点：绝不判 PAUSED（编造起点）、也绝不判 ACTIVE（假造漏服）
        listOf(migrationMs - 1, migrationMs - 1_000_000, 0L).forEach { t ->
            assertEquals("t=$t 必须是 UNKNOWN", MedicationStatus.UNKNOWN, d.statusAt(events, t))
        }
        // 迁移后的任意时点：已证 PAUSED
        assertTrue(d.pausedAt(events, migrationMs + 1))
    }

    @Test
    fun `first resume does not reinterpret the days before it`() {
        val resumeAt = migrationMs + 5 * 86_400_000L
        val events = listOf(baseline(), event(2, MedicationStatusKind.RESUME, resumeAt, recordedAtMs = migrationMs + 1))
        // t < M：UNKNOWN
        assertEquals(MedicationStatus.UNKNOWN, d.statusAt(events, migrationMs - 1))
        // M <= t < D：PAUSED
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, migrationMs))
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, resumeAt - 1))
        // t >= D：ACTIVE
        assertEquals(MedicationStatus.ACTIVE, d.statusAt(events, resumeAt))
        // D 之前（无论未知还是暂停）都不投影；D 起才活跃
        assertFalse(d.doseActiveAt(events, migrationMs))
        assertFalse(d.doseActiveAt(events, resumeAt - 1))
        assertTrue(d.doseActiveAt(events, resumeAt))
    }

    @Test
    fun `first non resume event keeps the unknown window unknown`() {
        val pauseAt = migrationMs + 2 * 86_400_000L
        val events = listOf(baseline(), event(2, MedicationStatusKind.PAUSE, pauseAt, recordedAtMs = migrationMs + 1))
        assertEquals(MedicationStatus.UNKNOWN, d.statusAt(events, migrationMs - 1))
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, migrationMs))
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, pauseAt))
    }

    @Test
    fun `deriveCurrentStatus reads events not the cache - baseline wins over a stale active cache`() {
        // 对抗 §2.A：把缓存当作 false（活跃），派生仍必须为 true（基线行压过缓存）
        val events = listOf(baseline())
        assertTrue(d.deriveCurrentStatus(events, migrationMs))
        // 首个 RESUME 后派生变为 false（活跃），与缓存一致
        val resumed = events + event(2, MedicationStatusKind.RESUME, migrationMs + 1, recordedAtMs = migrationMs + 1)
        assertFalse(d.deriveCurrentStatus(resumed, migrationMs + 2))
    }

    @Test
    fun `a baseline only legacy medication never has an empty event table`() {
        // 迁移后遗留归档药必有基线行 ⇒ 事件表非空 ⇒ 不可能被空列表翻活跃
        val events = listOf(baseline())
        assertTrue(events.isNotEmpty())
        assertEquals(MedicationStatus.PAUSED, d.statusAt(events, migrationMs + 1000))
    }
}
