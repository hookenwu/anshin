package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.MedicationStatusEvent
import kotlinx.coroutines.flow.Flow

/**
 * 药品状态事件仓储（append-only）——docs/medication-status-spec.md §4。
 *
 * 只有 append：**没有** update / delete 接口；改错 = 追加取代事件。
 * 派生（`statusAt` 等）是纯函数，见 `MedicationStatusDerivation`。
 */
interface MedicationStatusRepository {

    /** 某药的全部事件（响应式）。 */
    fun observeEvents(medicationId: Long): Flow<List<MedicationStatusEvent>>

    /** 某药的全部事件（一次性）。 */
    suspend fun getEventsOnce(medicationId: Long): List<MedicationStatusEvent>

    /** 按 id 单取（更正路径定位被取代者）。 */
    suspend fun getEventById(id: Long): MedicationStatusEvent?

    /** 全库事件（响应式），供历史 / 依从率按 medicationId 分组派生。 */
    fun observeAllEvents(): Flow<List<MedicationStatusEvent>>

    /** 某成员名下全部药的事件（一次性），供全量 reconcile 逐药派生。 */
    suspend fun getEventsForRecipientOnce(recipientId: Long): List<MedicationStatusEvent>

    /** append 一条事件（含可空不变量与 supersedesId 校验，见 DAO）。 */
    suspend fun insertEvent(event: MedicationStatusEvent): Long
}
