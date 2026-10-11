package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 取代链派生（更正 D）——docs/medication-status-spec.md §9.1。
 *
 * 覆盖：多层取代、§5.2 反例回归、VOID、重复取代、顺序无关、悬挂/跨药退化，以及随机取代图的
 * 合流性质测试（更正 K，§9.1.8）。
 */
class MedicationStatusDerivationTest {

    private val d = MedicationStatusDerivation

    private fun ev(
        id: Long,
        kind: String,
        effectiveAtMs: Long?,
        recordedAtMs: Long = id,
        supersedesId: Long? = null,
        medicationId: Long = 1,
    ) = MedicationStatusEvent(
        id = id,
        medicationId = medicationId,
        kind = kind,
        effectiveAtMs = effectiveAtMs,
        recordedAtMs = recordedAtMs,
        supersedesId = supersedesId,
    )

    private fun pause(id: Long, at: Long, rec: Long = id) = ev(id, MedicationStatusKind.PAUSE, at, rec)
    private fun resume(id: Long, at: Long, rec: Long = id) = ev(id, MedicationStatusKind.RESUME, at, rec)
    private fun void(id: Long, sup: Long, rec: Long = id) =
        ev(id, MedicationStatusKind.VOID, effectiveAtMs = null, recordedAtMs = rec, supersedesId = sup)

    // ── §9.1.1 多层取代 A←B←C ⇒ live={C}，A、B 永不生效 ──────────────────────────────
    @Test
    fun `multi level supersede keeps only the chain tail`() {
        val a = pause(1, 100)
        val b = resume(2, 200, rec = 2).copy(supersedesId = 1)
        val c = pause(3, 300, rec = 3).copy(supersedesId = 2)
        val live = d.liveEvents(listOf(a, b, c))
        assertEquals(listOf(3L), live.map { it.id })
        // 祖先永不生效：300 时刻只有 C（PAUSE）
        assertTrue(d.pausedAt(listOf(a, b, c), 300))
        // 与「只有 C」时逐字节一致
        assertEquals(d.pausedAt(listOf(c), 300), d.pausedAt(listOf(a, b, c), 300))
    }

    // ── §9.1.2 / §5.2 反例回归：旧谓词会误判为「已暂停」 ───────────────────────────────
    @Test
    fun `superseded ancestor never revives even when it carries no supersedesId`() {
        val eA = pause(2003, 300, rec = 1)
        val eB = resume(2004, 200, rec = 2).copy(supersedesId = 2003)
        // 意图：eB 取代 eA ⇒ live={eB} ⇒ now=300 时 RESUME ⇒ 未暂停
        assertEquals(listOf(2004L), d.liveEvents(listOf(eA, eB)).map { it.id })
        assertFalse(d.pausedAt(listOf(eA, eB), 300))
        // 旧谓词（保留 supersedesId==null 者）会把 eA 纳入并按 (effectiveAtMs) 取胜 ⇒ 误判暂停。
        // 这里以「只留活行」的断言把它钉死。
        assertEquals(listOf(2004L), d.liveEvents(listOf(eA, eB)).map { it.id })
    }

    // ── §9.1.3 VOID：回退到被作废行之前的那条活状态行 ──────────────────────────────────
    @Test
    fun `void kills its target and falls back to the previous live state row`() {
        val a = pause(1, 100)
        val b = void(2, sup = 1)
        assertEquals(listOf(2L), d.liveEvents(listOf(a, b)).map { it.id })
        // 无更早活状态行 ⇒ 无状态 ⇒ 未暂停
        assertFalse(d.pausedAt(listOf(a, b), 100))

        val z = resume(0, 50, rec = 0)
        assertEquals(listOf(0L, 2L), d.liveEvents(listOf(z, a, b)).map { it.id }.sorted())
        // 回退到 Z（RESUME）而非作废行本身
        assertFalse(d.pausedAt(listOf(z, a, b), 100))
    }

    // ── §9.1.4 重复取代：败者遮蔽保留，结果与「只有胜者」一致 ────────────────────────────
    @Test
    fun `duplicate supersede of one target resolves deterministically to the latest claimant`() {
        val a = pause(1, 100)
        val r1 = resume(2, 150, rec = 10).copy(supersedesId = 1)
        val r2 = resume(3, 160, rec = 20).copy(supersedesId = 1)
        val all = listOf(a, r1, r2)
        assertEquals(listOf(3L), d.liveEvents(all).map { it.id }) // r2 胜（recordedAtMs 更大）
        assertFalse(d.pausedAt(all, 200))
        // 与「只有胜者一条」逐字节一致
        assertEquals(d.pausedAt(listOf(r2), 200), d.pausedAt(all, 200))
        // 败者行仍在表内（append-only）
        assertEquals(a, all.first { it.id == 1L })
        assertTrue(all.any { it.id == 2L })
    }

    // ── §9.1.5 冲突取代顺序无关（confluent） ────────────────────────────────────────────
    @Test
    fun `derivation is insertion order independent`() {
        val events = listOf(
            pause(1, 100),
            resume(2, 150, rec = 2).copy(supersedesId = 1),
            void(3, sup = 2, rec = 3),
            pause(4, 400, rec = 4).copy(supersedesId = 3),
        )
        val grid = (90L..420L step 10).toList()
        val first = grid.map { d.statusAt(events, it) }
        for (perm in listOf(events.reversed(), events.shuffled(), events.sortedBy { it.recordedAtMs })) {
            assertEquals(d.liveEvents(events).map { it.id }.sorted(), d.liveEvents(perm).map { it.id }.sorted())
            assertEquals(first, grid.map { d.statusAt(perm, it) })
        }
    }

    // ── §9.1.7 悬挂 / 跨药 supersedesId 安全退化 ───────────────────────────────────────
    @Test
    fun `dangling and cross medication supersede references are ignored`() {
        val a = pause(1, 100)
        val dangling = resume(2, 150, rec = 2).copy(supersedesId = 999)
        val other = ev(3, MedicationStatusKind.RESUME, 160, recordedAtMs = 3, supersedesId = 1, medicationId = 2)
        // 悬挂（999 不在集合）不抑制任何事件；跨药（medicationId 不匹配）也不抑制
        val live = d.liveEvents(listOf(a, dangling, other))
        assertTrue("悬挂/跨药引用不得抑制 A", live.any { it.id == 1L })
    }

    // ── §9.1.6 环防止（性质）：结构上只引用更旧 id ⇒ 取代图恒可拓扑排序 ──────────────────
    @Test
    fun `supersede graph is always acyclic under append only with strictly smaller references`() {
        val rng = Random(42)
        repeat(200) {
            val events = randomEventGraph(rng)
            assertTrue("随机取代图必须可拓扑排序（无环）", isAcyclic(events))
        }
    }

    // ── §9.1.8 合流性质测试 P1–P4 ──────────────────────────────────────────────────────
    @Test
    fun `P1 derivation is invariant under shuffled insertion order`() {
        val rng = Random(7)
        repeat(300) {
            val events = randomEventGraph(rng)
            val grid = (0L..40L).toList()
            val base = grid.map { d.statusAt(events, it) }
            val baseLive = d.liveEvents(events).map { it.id }.sorted()
            repeat(5) {
                val perm = events.shuffled(rng)
                assertEquals(baseLive, d.liveEvents(perm).map { it.id }.sorted())
                assertEquals(base, grid.map { d.statusAt(perm, it) })
            }
        }
    }

    @Test
    fun `P2 single pass liveEvents agrees with a fixpoint reference model`() {
        // 参考模型只做 R1（反复删除被指向者）；为使其与含 R4 遮蔽的单遍实现可比，
        // 这里限定「同一目标不被多条活行取代」（无重复声称）。
        val rng = Random(99)
        repeat(300) {
            val events = randomEventGraph(rng, uniqueTargets = true)
            assertEquals(referenceLiveIds(events).sorted(), d.liveEvents(events).map { it.id }.sorted())
        }
    }

    @Test
    fun `P3 multi level plus duplicate claim resolves to the deterministic live set`() {
        // A←B←C（多层）与 A←D（重复声称 A）同图 ⇒ live = {C, D}
        val a = pause(1, 100)
        val b = resume(2, 200, rec = 2).copy(supersedesId = 1)
        val c = pause(3, 300, rec = 3).copy(supersedesId = 2)
        val dd = resume(4, 250, rec = 4).copy(supersedesId = 1)
        val live = d.liveEvents(listOf(a, b, c, dd)).map { it.id }.sorted()
        assertEquals(listOf(3L, 4L), live)
        // 400 时刻：ORDER 最大者是 C（effectiveAtMs=300 > 250）⇒ PAUSE
        assertTrue(d.pausedAt(listOf(a, b, c, dd), 400))
    }

    @Test
    fun `P4 victims are monotone - once pointed to, a row stays dead`() {
        val rng = Random(1234)
        repeat(200) {
            val events = randomEventGraph(rng)
            val targets = events.mapNotNull { it.supersedesId }.toSet()
            val liveIds = d.liveEvents(events).map { it.id }.toSet()
            assertTrue("被指向者永不出现在活集里", liveIds.intersect(targets).isEmpty())
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────
    private fun randomEventGraph(rng: Random, uniqueTargets: Boolean = false): List<MedicationStatusEvent> {
        val n = rng.nextInt(2, 12)
        val kinds = listOf(
            MedicationStatusKind.PAUSE,
            MedicationStatusKind.RESUME,
            MedicationStatusKind.DISCONTINUE,
            MedicationStatusKind.VOID,
        )
        val result = mutableListOf<MedicationStatusEvent>()
        val usedTargets = mutableSetOf<Long>()
        for (id in 1L..n) {
            val kind = kinds[rng.nextInt(kinds.size)]
            val candidates = (1L until id).filter { !uniqueTargets || it !in usedTargets }
            val sup = if (candidates.isNotEmpty() &&
                rng.nextInt(100) < 60
            ) {
                candidates[rng.nextInt(candidates.size)]
            } else {
                null
            }
            sup?.let { usedTargets += it }
            val effective = if (kind == MedicationStatusKind.VOID) null else rng.nextLong(0, 30)
            result += MedicationStatusEvent(
                id = id,
                medicationId = 1,
                kind = kind,
                effectiveAtMs = effective,
                recordedAtMs = rng.nextLong(0, 30),
                supersedesId = sup,
            )
        }
        return result
    }

    /** 独立「不动点」解析器：反复删除被指向者直到稳定（§9.1.8 P2 参考模型）。 */
    private fun referenceLiveIds(events: List<MedicationStatusEvent>): List<Long> {
        var current = events
        while (true) {
            val targets = current.mapNotNull { it.supersedesId }.toSet()
            val next = current.filter { it.id !in targets }
            if (next.size == current.size) return next.map { it.id }
            current = next
        }
    }

    private fun isAcyclic(events: List<MedicationStatusEvent>): Boolean {
        val byId = events.associateBy { it.id }
        for (e in events) {
            var cursor: MedicationStatusEvent? = e
            val seen = mutableSetOf<Long>()
            while (cursor != null) {
                if (!seen.add(cursor.id)) return false
                cursor = cursor.supersedesId?.let { byId[it] }
            }
        }
        return true
    }
}
