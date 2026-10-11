package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 药品状态事件种类 = **代码常量**（对齐 `care_event_logs` 的 kind 存法；不用 enum Converter）。
 *
 * docs/medication-status-spec.md §4：
 * - [LEGACY_BASELINE] 迁移补种：遗留归档药的基线（生效日显式未知）；系统写，用户不可写；
 * - [PAUSE] 临时停用（可能恢复）；[RESUME] 恢复；[DISCONTINUE] 归档 / 永久终止；
 * - [VOID] 作废（配合 `supersedesId` 使用；本身不承载状态，见 §5 R3）。
 */
object MedicationStatusKind {
    const val LEGACY_BASELINE = "LEGACY_BASELINE"
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val DISCONTINUE = "DISCONTINUE"
    const val VOID = "VOID"

    /** 迁移基线唯一允许的 kind（生效日可为 NULL）。 */
    val all = listOf(LEGACY_BASELINE, PAUSE, RESUME, DISCONTINUE, VOID)

    /** 状态承载 kind（VOID 不承载，见 §5 R3）。 */
    val statusBearing = listOf(LEGACY_BASELINE, PAUSE, RESUME, DISCONTINUE)
}

/**
 * 药品状态事件（append-only、可取代）——docs/medication-status-spec.md §4 D1/D10。
 *
 * - **append-only**：不就地编辑、不物理删除；改错 = **追加取代事件**（`supersedesId`）。
 * - [effectiveAtMs] 可空的**唯一**情形 ⇔ `kind == LEGACY_BASELINE`（生效日显式未知）；
 *   其余 kind 必非空（`insertEvent` 强制，违反抛 [InvalidStatusEvent]）。
 * - [supersedesId] 普通可空列 + 索引（**不建自引用 FK**）；不变量：同药且 `< 本行 id`。
 * - 删除药物 → FK CASCADE 连带状态事件（§11 E9）。
 */
@Entity(
    tableName = "medication_status_events",
    foreignKeys = [
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("medicationId"),
        Index(value = ["medicationId", "effectiveAtMs"]),
    ],
)
data class MedicationStatusEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    /** 取 [MedicationStatusKind.*]。 */
    val kind: String,
    /** 状态「实际生效」时刻（可倒填；首期不允许未来）；**NULL ⇔ kind == LEGACY_BASELINE**。 */
    val effectiveAtMs: Long? = null,
    /** 录入时刻（与生效独立）。基线 = 迁移时刻（认知地平线 `M`）。 */
    val recordedAtMs: Long,
    /** 可选原因 / 备注。 */
    val reason: String? = null,
    /** 更正：指向被本事件取代的旧事件（同药、且 id 更小）；不建自引用 FK。 */
    val supersedesId: Long? = null,
)

/** `insertEvent` 的可空不变量被违反（`effectiveAtMs` 与 `LEGACY_BASELINE` 不匹配）。 */
class InvalidStatusEvent(message: String) : IllegalArgumentException(message)

/** `supersedesId` 指向不存在的行 / 跨药 / 非更旧行（结构上杜绝环，§5 R6）。 */
class InvalidSupersedeReference(message: String) : IllegalArgumentException(message)
