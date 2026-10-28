package com.driezy.medlog.data.model

/**
 * `MedicationDao.updatePlan` 的三种结局（docs/medication-time-offset.md §3.2）。
 *
 * 只区分"写了修订 / 没写"，供批量路径判断是否需要重排；时间戳与内容判定仍全部在 DAO 内。
 */
enum class PlanWriteResult {
    /** 计划字段逐字相同 ⇒ 原地写整行、0 条修订、边界不动。 */
    NoOp,

    /** 内容变化且时间戳严格前进 ⇒ 插入旧计划 + 前移边界。 */
    Advanced,
}

/**
 * 计划内容变了，但 `changedAt` 没能严格越过当前边界（同毫秒重复写 / 时钟回拨）。
 *
 * **拒绝，不覆盖**：抛在 `@Transaction` 内 ⇒ 一行都不写、修订一条不建（§3.2 分支 3）。
 * 调用方不得"给时间戳 +1ms"或 `coerce` 边界——那等于让代码替系统时钟撒谎，
 * 并把过去按新计划重写一遍。
 */
class PlanBoundaryNotAdvancedException(val medicationId: Long, val boundaryMs: Long, val requestedMs: Long) :
    IllegalStateException(
        "Plan boundary did not advance for medication $medicationId: " +
            "boundary=$boundaryMs, requested=$requestedMs (duplicate-timestamp write or clock rollback)",
    )
