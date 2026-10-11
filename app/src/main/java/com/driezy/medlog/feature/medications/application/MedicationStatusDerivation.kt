package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind

/**
 * 某时刻的药品状态（docs/medication-status-spec.md §5.1）。
 * - [ACTIVE] 应服；[PAUSED] 已证暂停 / 归档；[UNKNOWN] 迁移地平线前的不可考区间。
 */
enum class MedicationStatus { ACTIVE, PAUSED, UNKNOWN }

/**
 * 药品状态派生——**唯一**的纯判定模块（docs/medication-status-spec.md §5）。
 *
 * 投影（`FuturePlanCalculator`）、依从率（`MedicationAdherenceCalculator`）、历史 UI、
 * 提醒排程与接收端守卫**只**经本对象判定「是否应服 / 是否暂停 / 是否未知」，**禁止**任何路径
 * 内联 `pausedAt(dayStart)` 之类的 day 级判定（§5.3、§9.7）。
 *
 * 整链一次算完（更正 D）：`liveEvents` 用「有没有行指向我」判定死/活，而不是「我自己有没有
 * `supersedesId`」——后者会复活多层链的祖先（§5.2 反例）。
 */
object MedicationStatusDerivation {

    /**
     * 取活行（R1–R5）：
     * - **R1 指针规则**：一行是死的 ⇔ 事件集里有活行指向它（`dead = {supersedesId}`，`live = E \ dead`）；
     * - **R4 重复取代**：同一目标被 ≥2 条活行取代 ⇒ 只留 `(recordedAtMs, id)` 最大者，其余「遮蔽」（保留行）。
     *
     * 顺序无关（confluent）：结果是事件集 `E` 的纯函数（§9.1.8 性质测试）。
     */
    fun liveEvents(events: List<MedicationStatusEvent>): List<MedicationStatusEvent> {
        // R1 指针规则：一行是死的 ⇔ 事件集里有活行指向它。
        // 「活行」= 确实存在于该药事件集的行：**悬挂**（指向不存在的 id）与**跨药**
        // （指向的行存在但 medicationId 不符）引用都**不算**，即安全退化、不抑制任何事件
        // （§4 supersedesId 不变量「同药」、§5.1 R1）。`insertEvent` 已在写入端拒绝跨药引用，
        // 这里是派生端的同类保证（defense-in-depth）。
        val byId = events.associateBy { it.id }
        val targets = events.mapNotNull { row ->
            row.supersedesId?.takeIf { targetId -> byId[targetId]?.medicationId == row.medicationId }
        }.toSet()
        val afterR1 = events.filter { it.id !in targets }
        val shadowed = mutableSetOf<Long>()
        events.filter { it.supersedesId != null && it.supersedesId in targets }
            .groupBy { it.supersedesId!! }
            .forEach { (_, group) ->
                val competitors = group.filter { it.id !in targets }
                if (competitors.size > 1) {
                    val winner = competitors.maxWith(compareBy({ it.recordedAtMs }, { it.id }))
                    competitors.filter { it.id != winner.id }.forEach { shadowed += it.id }
                }
            }
        return afterR1.filter { it.id !in shadowed }
    }

    /** 全序比较键：`(effectiveAtMs, recordedAtMs, id)`，基线的 `effectiveAtMs=NULL` 取下界（恒被带日期行压过，§5 R7）。 */
    private val ORDER = compareBy<MedicationStatusEvent>(
        { it.effectiveAtMs ?: Long.MIN_VALUE },
        { it.recordedAtMs },
        { it.id },
    )

    /** 状态承载 kind 的活行（排除 VOID；§5 R3）。 */
    private fun liveStatusEvents(events: List<MedicationStatusEvent>): List<MedicationStatusEvent> =
        liveEvents(events).filter { it.kind in MedicationStatusKind.statusBearing }

    /**
     * `t` 时刻的状态（§5.1）：
     * - 候选 = 活的状态行里 `effectiveAtMs == null || effectiveAtMs <= t` 者，取 [ORDER] 最大；
     * - `PAUSE`/`DISCONTINUE` → [MedicationStatus.PAUSED]；`RESUME` → [MedicationStatus.ACTIVE]；
     * - `LEGACY_BASELINE` → `t >= 基线.recordedAtMs` 时 [MedicationStatus.PAUSED]，否则 [MedicationStatus.UNKNOWN]；
     * - 无候选（R8）→ [MedicationStatus.ACTIVE]。
     */
    fun statusAt(events: List<MedicationStatusEvent>, t: Long): MedicationStatus {
        val latest = liveStatusEvents(events)
            .filter { it.effectiveAtMs?.let { e -> e <= t } ?: true }
            .maxWithOrNull(ORDER)
        return when (latest?.kind) {
            MedicationStatusKind.PAUSE, MedicationStatusKind.DISCONTINUE -> MedicationStatus.PAUSED
            MedicationStatusKind.RESUME -> MedicationStatus.ACTIVE
            MedicationStatusKind.LEGACY_BASELINE ->
                if (t >= latest.recordedAtMs) MedicationStatus.PAUSED else MedicationStatus.UNKNOWN
            else -> MedicationStatus.ACTIVE
        }
    }

    fun pausedAt(events: List<MedicationStatusEvent>, t: Long): Boolean = statusAt(events, t) == MedicationStatus.PAUSED

    fun unknownAt(events: List<MedicationStatusEvent>, t: Long): Boolean =
        statusAt(events, t) == MedicationStatus.UNKNOWN

    /** **唯一**的 dose 级谓词：投影 / 依从率 / 历史 UI 都经此判定「该 occurrence 是否应服」（§5.3）。 */
    fun doseActiveAt(events: List<MedicationStatusEvent>, scheduledInstant: Long): Boolean =
        statusAt(events, scheduledInstant) == MedicationStatus.ACTIVE

    /** 当前活状态行承载的 kind（CHANGE 比较用它，不看暂停布尔；§5.4）。 */
    fun liveStatusKind(events: List<MedicationStatusEvent>, now: Long): String? = liveStatusEvents(events)
        .filter { it.effectiveAtMs?.let { e -> e <= now } ?: true }
        .maxWithOrNull(ORDER)
        ?.kind

    /** 派生当前是否暂停（= 兼容缓存 `medications.isArchived` 的权威语义）；**不读缓存**（更正 I）。 */
    fun deriveCurrentStatus(events: List<MedicationStatusEvent>, now: Long): Boolean = pausedAt(events, now)
}
