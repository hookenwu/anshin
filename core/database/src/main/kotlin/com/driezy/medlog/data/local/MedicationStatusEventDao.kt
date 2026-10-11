package com.driezy.medlog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.driezy.medlog.data.model.InvalidStatusEvent
import com.driezy.medlog.data.model.InvalidSupersedeReference
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import kotlinx.coroutines.flow.Flow

/**
 * 药品状态事件 DAO（append-only）——docs/medication-status-spec.md §4。
 *
 * **没有** update / delete 接口：改错 = 追加取代事件（`supersedesId`），更正 = 新行。
 * 成员作用域由仓储承担（DAO 只按 medicationId / recipientId 取行）。
 */
@Dao
interface MedicationStatusEventDao {

    /** 某药的全部事件，最新录入在前（派生在内存里做，见 `MedicationStatusDerivation`）。 */
    @Query("SELECT * FROM medication_status_events WHERE medicationId = :medicationId ORDER BY id")
    fun getEventsForMedication(medicationId: Long): Flow<List<MedicationStatusEvent>>

    /** 某药的全部事件一次性读取。 */
    @Query("SELECT * FROM medication_status_events WHERE medicationId = :medicationId ORDER BY id")
    suspend fun getEventsForMedicationOnce(medicationId: Long): List<MedicationStatusEvent>

    /** 全库事件（供历史 / 依从率按 medicationId 分组派生）。 */
    @Query("SELECT * FROM medication_status_events ORDER BY id")
    fun getAllEvents(): Flow<List<MedicationStatusEvent>>

    /** 某药最新一条事件（取不到返回 null）。 */
    @Query("SELECT * FROM medication_status_events WHERE medicationId = :medicationId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestEventOnce(medicationId: Long): MedicationStatusEvent?

    /** 按成员取全部事件（JOIN medications），供全量 reconcile 逐药派生。 */
    @Query(
        """
        SELECT medication_status_events.* FROM medication_status_events
        INNER JOIN medications ON medications.id = medication_status_events.medicationId
        WHERE medications.careRecipientId = :recipientId
        ORDER BY medication_status_events.id
        """,
    )
    suspend fun getEventsForRecipientOnce(recipientId: Long): List<MedicationStatusEvent>

    /** 按 id 单取（supersedesId 校验用）。 */
    @Query("SELECT * FROM medication_status_events WHERE id = :id")
    suspend fun getEventById(id: Long): MedicationStatusEvent?

    @Query("SELECT COUNT(*) FROM medication_status_events WHERE medicationId = :medicationId")
    suspend fun countEvents(medicationId: Long): Int

    @Insert
    suspend fun insertRaw(event: MedicationStatusEvent): Long

    /**
     * append 一条事件，前置校验（§4）：
     * - 可空不变量：`effectiveAtMs == null ⇔ kind == LEGACY_BASELINE`；
     * - `supersedesId` 必须指向**同药**的**既有**行（既有 ⇒ id 更旧 ⇒ 结构上杜绝环，§5 R6）。
     *
     * 违反抛 [InvalidStatusEvent] / [InvalidSupersedeReference]。整个校验 + 插入在同一事务。
     */
    @Transaction
    suspend fun insertEvent(event: MedicationStatusEvent): Long {
        val isBaseline = event.kind == MedicationStatusKind.LEGACY_BASELINE
        if (isBaseline != (event.effectiveAtMs == null)) {
            throw InvalidStatusEvent(
                "effectiveAtMs 可空的唯一情形是 LEGACY_BASELINE：kind=${event.kind} effectiveAtMs=${event.effectiveAtMs}",
            )
        }
        val supersedes = event.supersedesId
        if (supersedes != null) {
            val target = getEventById(supersedes)
                ?: throw InvalidSupersedeReference("supersedesId=$supersedes 不存在")
            if (target.medicationId != event.medicationId) {
                throw InvalidSupersedeReference(
                    "supersedesId=$supersedes 跨药（${target.medicationId} != ${event.medicationId}）",
                )
            }
        }
        return insertRaw(event)
    }
}
