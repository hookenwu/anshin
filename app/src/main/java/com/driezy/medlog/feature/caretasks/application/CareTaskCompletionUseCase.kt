package com.driezy.medlog.feature.caretasks.application

import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.LogRevisionType
import com.driezy.medlog.data.repository.CareTaskRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * 照护事项的完成语义（docs/care-tasks.md §3）。
 *
 * 与用药的 [com.driezy.medlog.feature.medications.application.ToggleMedicationDoseUseCase] 对齐：
 * 这是照护事项**唯一的命令入口**，今日页 / 详情页 / 通知动作都走它。
 *
 * - 打卡型（TOGGLE）：[complete] → `DONE`；[skip] → `SKIPPED`；[undo] → 物理删除该次记录；
 * - 时长型（DURATION）：[start] → `IN_PROGRESS` + 开始时刻；再次 [complete] → 补结束时刻并算出时长；
 * - 时长型同样允许 [skip]；
 * - **不做自动超时结束**：不替用户编造完成时间（决策 2 的明确边界）。
 *
 * 每次记录按 `(careTaskId, scheduledTimeMs)` 唯一：同一时间槽反复操作只会就地更新同一行，
 * 不会产生第二行。读改写包在事务里。
 *
 * 尚未包含「完成后按间隔顺延下一次提醒」——那属于排期接入（照护事项的闹钟尚未开始排期），
 * 届时在此命令成功后追加一次重排即可，领域侧能力已在 T4 备好（[com.driezy.medlog.data.model.completionInterval]）。
 */
class CareTaskCompletionUseCase @Inject constructor(
    private val transactionRunner: TransactionRunner,
    private val careTasks: CareTaskRepository,
    private val clock: Clock,
) {

    /**
     * 一键完成。
     *
     * 打卡型直接记为 `DONE`；时长型若此前已 [start]，则保留开始时刻、补上结束时刻并算出时长。
     */
    suspend fun complete(
        taskId: Long,
        scheduledTimeMs: Long,
        notes: String = "",
        postureNote: String? = null,
    ): CareTaskLog = mutate(taskId, scheduledTimeMs) { existing, nowMs ->
        val startMs = existing?.actualStartMs
        log(taskId, scheduledTimeMs, existing).copy(
            status = CareTaskLogStatus.DONE,
            actualStartMs = startMs,
            actualEndMs = nowMs,
            actualDurationMinutes = durationMinutes(startMs, nowMs),
            notes = notes.ifEmpty { existing?.notes.orEmpty() },
            postureNote = postureNote ?: existing?.postureNote,
            updatedAtMs = nowMs,
        )
    }

    /** 时长型：开始。重复开始不覆盖最初的开始时刻（避免把已用时长算短）。 */
    suspend fun start(taskId: Long, scheduledTimeMs: Long, postureNote: String? = null): CareTaskLog =
        mutate(taskId, scheduledTimeMs) { existing, nowMs ->
            log(taskId, scheduledTimeMs, existing).copy(
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = existing?.actualStartMs ?: nowMs,
                actualEndMs = null,
                actualDurationMinutes = null,
                postureNote = postureNote ?: existing?.postureNote,
                updatedAtMs = nowMs,
            )
        }

    /**
     * 跳过本次。
     *
     * 已开始过的（时长型）保留开始时刻——"开始过但没做完"是事实；
     * 结束时刻与时长一律清空，不编造完成。
     */
    suspend fun skip(taskId: Long, scheduledTimeMs: Long, notes: String = ""): CareTaskLog =
        mutate(taskId, scheduledTimeMs) { existing, nowMs ->
            log(taskId, scheduledTimeMs, existing).copy(
                status = CareTaskLogStatus.SKIPPED,
                actualStartMs = existing?.actualStartMs,
                actualEndMs = null,
                actualDurationMinutes = null,
                notes = notes.ifEmpty { existing?.notes.orEmpty() },
                updatedAtMs = nowMs,
            )
        }

    /** 撤销：物理删除该次记录（与用药的撤销语义一致）。 */
    suspend fun undo(taskId: Long, scheduledTimeMs: Long) {
        transactionRunner.withTransaction {
            careTasks.deleteLogForScheduledTime(taskId, scheduledTimeMs)
        }
    }

    /** 该时间槽当前的记录；`IN_PROGRESS` 即"进行中"。 */
    suspend fun logFor(taskId: Long, scheduledTimeMs: Long): CareTaskLog? =
        careTasks.getLogForScheduledTime(taskId, scheduledTimeMs)

    private suspend fun mutate(
        taskId: Long,
        scheduledTimeMs: Long,
        transform: (existing: CareTaskLog?, nowMs: Long) -> CareTaskLog,
    ): CareTaskLog = transactionRunner.withTransaction {
        val nowMs = clock.millis()
        val existing = careTasks.getLogForScheduledTime(taskId, scheduledTimeMs)
        val updated = transform(existing, nowMs)
        val rowId = careTasks.upsertLog(updated)
        if (updated.id == 0L) updated.copy(id = rowId) else updated
    }

    /**
     * 保留既有行的标识与首次写入语义，并把"改写已终结记录"标成编辑。
     *
     * 时长型的 `IN_PROGRESS → DONE` 是**同一条记录的正常生命周期**，不算编辑；
     * 只有把已经终结（`DONE` / `SKIPPED`）的记录再改一次才算编辑，跨天则记为回溯编辑。
     */
    private fun log(taskId: Long, scheduledTimeMs: Long, existing: CareTaskLog?) = CareTaskLog(
        id = existing?.id ?: 0L,
        careTaskId = taskId,
        scheduledTimeMs = scheduledTimeMs,
        status = existing?.status ?: CareTaskLogStatus.DONE,
        actualStartMs = existing?.actualStartMs,
        actualEndMs = existing?.actualEndMs,
        actualDurationMinutes = existing?.actualDurationMinutes,
        postureNote = existing?.postureNote,
        notes = existing?.notes.orEmpty(),
        createdAtMs = existing?.createdAtMs ?: clock.millis(),
        updatedAtMs = existing?.updatedAtMs,
        revisionType = revisionTypeFor(existing, scheduledTimeMs),
    )

    private fun revisionTypeFor(existing: CareTaskLog?, scheduledTimeMs: Long): LogRevisionType {
        val previous = existing ?: return LogRevisionType.ORIGINAL
        val wasTerminal = previous.status == CareTaskLogStatus.DONE ||
            previous.status == CareTaskLogStatus.SKIPPED
        if (!wasTerminal) return previous.revisionType
        val zone = clock.zone
        val sameDay = LocalDate.ofInstant(Instant.ofEpochMilli(scheduledTimeMs), zone) ==
            LocalDate.ofInstant(Instant.ofEpochMilli(clock.millis()), zone)
        return if (sameDay) LogRevisionType.SAME_DAY_EDIT else LogRevisionType.RETROACTIVE_EDIT
    }

    /** 时长（分钟）：起止齐全且顺序正确才算得出，否则 null（宁可缺数据也不编造）。 */
    private fun durationMinutes(startMs: Long?, endMs: Long): Int? {
        val start = startMs?.takeIf { it <= endMs } ?: return null
        return Duration.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(endMs))
            .toMinutes()
            .toInt()
    }
}
