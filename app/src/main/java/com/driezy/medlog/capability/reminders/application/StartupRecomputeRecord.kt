package com.driezy.medlog.capability.reminders.application

import kotlinx.coroutines.flow.Flow

/**
 * 一次启动重算的结局类别（docs/medication-time-offset.md §3.6）。
 *
 * 只有三种：真的收敛了、没有可重算的成员、以及整批被拒/失败。没有"部分成功"这一档——
 * 批量入口要么整批提交，要么整批回滚（§3.5）。
 */
enum class StartupRecomputeStatus {
    /** 整批提交成功（含"零行变化"的幂等情形），提醒已重排。 */
    CONVERGED,

    /** 没有当前成员：没有可重算的范围，不读作息、不写库、不重排。 */
    SKIPPED_NO_RECIPIENT,

    /** 整批被拒或失败（抛出前整批已回滚），提醒**没有**重排。 */
    FAILED,
}

/**
 * 失败的稳定分类：**同一套词**既做日志标签、又落进持久标记，日志与 UI 不会各说各话。
 *
 * - [ILLEGAL_OFFSET]：`RoutineResyncConflictException`（偏移在当前作息下不可物化，具名冲突清单）；
 * - [PLAN_BOUNDARY]：`PlanBoundaryNotAdvancedException`（时钟回拨 / 同一毫秒重复写）；
 * - [OTHER]：其余任何异常（磁盘、取消以外的意外），原样记录类名与消息。
 */
enum class StartupRecomputeFailure(val logToken: String) {
    ILLEGAL_OFFSET("illegal-offset"),
    PLAN_BOUNDARY("plan-boundary"),
    OTHER("unexpected"),
}

/**
 * 启动重算的持久记录（§3.6）。**可观测、可重试**：下一次启动读它决定要从什么状态收敛，
 * UI 也能看到"上次启动没收敛，为什么"。
 *
 * 注意它是**边沿触发**的：稳态（连续成功且无变化）不会每次启动都重写一条，
 * 因此"重复启动零额外写入"对 DataStore 也成立。
 */
data class StartupRecomputeRecord(
    val status: StartupRecomputeStatus,
    val recipientId: Long,
    val failure: StartupRecomputeFailure? = null,
    val advanced: Int = 0,
    val noOp: Int = 0,
    /** 具名冲突逐行（药名/时段/偏移/结果钟点），已渲染成稳定文本；非冲突失败为空。 */
    val conflicts: List<String> = emptyList(),
    /** 异常类名 + 消息（不含堆栈；堆栈只在日志里）。 */
    val detail: String? = null,
    val atMs: Long = 0L,
    /** 连续失败次数（成功或跳过时归零）——重试可见性的最小载体。 */
    val attempts: Int = 0,
) {
    val converged: Boolean get() = status != StartupRecomputeStatus.FAILED
}

/**
 * 持久标记的读写口。实现落在 DataStore（**不是** Room：不新增表、不新增迁移，见 §3.6），
 * 只做可观测性，不参与收敛判定本身（收敛永远由"重跑同一条写路径"完成）。
 */
interface StartupRecomputeMarker {
    /** 最近一条记录（从未跑过为 null）。 */
    val state: Flow<StartupRecomputeRecord?>

    suspend fun latest(): StartupRecomputeRecord?

    suspend fun write(record: StartupRecomputeRecord)
}

/**
 * 标记的稳定编码（键值对）——持久层只负责把这些键写进 DataStore，
 * 因此编码/解码可以在 JVM 上逐字测（见 `StartupRecomputeRecordCodecTest`）。
 */
fun StartupRecomputeRecord.encodeStartupRecompute(): Map<String, String> = buildMap {
    put(KEY_STATUS, status.name)
    put(KEY_RECIPIENT, recipientId.toString())
    failure?.let { put(KEY_FAILURE, it.name) }
    put(KEY_ADVANCED, advanced.toString())
    put(KEY_NO_OP, noOp.toString())
    if (conflicts.isNotEmpty()) put(KEY_CONFLICTS, conflicts.joinToString(CONFLICT_SEPARATOR))
    detail?.let { put(KEY_DETAIL, it) }
    put(KEY_AT_MS, atMs.toString())
    put(KEY_ATTEMPTS, attempts.toString())
}

/** 解码失败（缺键 / 枚举名不认识）⇒ null：坏标记当作"没跑过"，下一次启动照常重算。 */
fun Map<String, String>.decodeStartupRecompute(): StartupRecomputeRecord? {
    val status = get(KEY_STATUS)?.let { name ->
        StartupRecomputeStatus.entries.firstOrNull { it.name == name }
    } ?: return null
    val recipient = get(KEY_RECIPIENT)?.toLongOrNull() ?: return null
    val failure = get(KEY_FAILURE)?.let { name ->
        StartupRecomputeFailure.entries.firstOrNull { it.name == name }
    }
    return StartupRecomputeRecord(
        status = status,
        recipientId = recipient,
        failure = failure,
        advanced = get(KEY_ADVANCED)?.toIntOrNull() ?: 0,
        noOp = get(KEY_NO_OP)?.toIntOrNull() ?: 0,
        conflicts = get(KEY_CONFLICTS)?.split(CONFLICT_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList(),
        detail = get(KEY_DETAIL),
        atMs = get(KEY_AT_MS)?.toLongOrNull() ?: 0L,
        attempts = get(KEY_ATTEMPTS)?.toIntOrNull() ?: 0,
    )
}

/** 持久键前缀：与用户设置同库不同前缀也安全，实现用的是独立 DataStore 文件。 */
const val STARTUP_RECOMPUTE_KEY_PREFIX = "startup_recompute_"

private const val CONFLICT_SEPARATOR = "\n"
private const val KEY_STATUS = "status"
private const val KEY_RECIPIENT = "recipient_id"
private const val KEY_FAILURE = "failure"
private const val KEY_ADVANCED = "advanced"
private const val KEY_NO_OP = "no_op"
private const val KEY_CONFLICTS = "conflicts"
private const val KEY_DETAIL = "detail"
private const val KEY_AT_MS = "at_ms"
private const val KEY_ATTEMPTS = "attempts"
