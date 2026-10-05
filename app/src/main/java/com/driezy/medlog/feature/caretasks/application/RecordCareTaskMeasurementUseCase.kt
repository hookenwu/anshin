package com.driezy.medlog.feature.caretasks.application

import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.HealthRecordSource
import com.driezy.medlog.data.model.HealthType
import com.driezy.medlog.data.repository.HealthRepository
import java.time.Clock
import javax.inject.Inject

/**
 * 一次过程数据的记录结果。UI 据此区分「已写入」与「本次已记录过、未重复写入」。
 */
sealed interface CareTaskMeasurementResult {
    /** 已写入健康表，[recordId] 是新行主键。 */
    data class Recorded(val recordId: Long) : CareTaskMeasurementResult

    /** 该时间槽的该指标已存在（[sourceCacheKey] 命中），本次未写库。 */
    data class Duplicate(val sourceCacheKey: String) : CareTaskMeasurementResult
}

/**
 * T8：把照护事项的过程数据（血氧 / 氧流量 / 读数次数）写进既有 [HealthRecord] 表。
 *
 * 设计约束（docs/care-tasks.md §5）：
 * - 复用既有健康表与 [HealthRepository]，**不新增表 / DAO / 迁移**；
 * - `source = MANUAL`（既有枚举值，不新增），`timestamp` / `confirmedAt` 取自注入的 [Clock]；
 * - 体位是分类值，归 `CareTaskLog`（见 [CareTaskCompletionUseCase] 的 `postureNote`），**不写进这里**；
 * - 每条记录用确定性 [sourceCacheKey] 防重：先问 [HealthRepository.hasSourceCacheKey]，
 *   命中即返回 [CareTaskMeasurementResult.Duplicate]，不再写第二行（另有唯一索引兜底）。
 *
 * 唯一索引是 `(careRecipientId, sourceCacheKey)`，而 [HealthRepository] 的查询/写入都按当前成员
 * 加作用域，因此不同成员的 key 相同也互不影响。
 */
class RecordCareTaskMeasurementUseCase @Inject constructor(
    private val health: HealthRepository,
    private val clock: Clock,
) {

    /**
     * 记录一次测量。
     *
     * @param scheduledTimeMs 具体时间槽（今日某次排期的计划时刻）；AS_NEEDED 用当前时刻。
     * @param secondaryValue 可选次值（当前三个指标都不用，保留给将来）。
     */
    suspend fun record(
        taskId: Long,
        scheduledTimeMs: Long,
        type: HealthType,
        value: Double,
        secondaryValue: Double? = null,
        notes: String = "",
    ): CareTaskMeasurementResult {
        val key = sourceCacheKey(taskId, scheduledTimeMs, type)
        if (health.hasSourceCacheKey(key)) return CareTaskMeasurementResult.Duplicate(key)

        val nowMs = clock.millis()
        val recordId = health.addRecord(
            HealthRecord(
                type = type.name,
                value = value,
                secondaryValue = secondaryValue,
                timestamp = nowMs,
                notes = notes,
                source = HealthRecordSource.MANUAL,
                sourceCacheKey = key,
                confirmedAt = nowMs,
            ),
        )
        return CareTaskMeasurementResult.Recorded(recordId)
    }

    companion object {
        /**
         * 确定性防重键：`caretask:<taskId>:<scheduledTimeMs>:<HealthType.name>`。
         *
         * 三段共同决定：
         * - 同一时间槽的**同一指标**重复记录 → 同 key → 判重，不产生第二行；
         * - 同一时间槽的**不同指标**（血氧 vs 氧流量 vs 次数）→ 不同 key → 各自可记录；
         * - 不同时间槽 → 不同 key。
         */
        fun sourceCacheKey(taskId: Long, scheduledTimeMs: Long, type: HealthType): String =
            "caretask:$taskId:$scheduledTimeMs:${type.name}"
    }
}
