package com.driezy.medlog.domain

/**
 * 照护事件「距上次发生的间隔」派生纯函数（docs/tracked-events-spec.md §4 D4 / §5 R4）。
 *
 * **间隔不落库**：永远由锚点（最新一条 `occurredAtMs`）读出后现算，因此补记/编辑/删除后
 * 只要锚点变了，间隔自动就是新的，没有冗余状态可失同步。
 *
 * 超期判据是**绝对时长**（`now - anchor >= thresholdDays * 一天`），与本地日界、夏令时（DST）
 * 无关——避免「本地日相减」在 DST 切换日产生 ±1 天误差。
 */
object CareEventInterval {

    const val MILLIS_PER_DAY = 86_400_000L

    /** 自锚点起的绝对时长（毫秒）；时钟回拨时钳到 0，绝不返回负值。无锚点返回 null。 */
    fun elapsedMs(anchorMs: Long?, nowMs: Long): Long? = anchorMs?.let { (nowMs - it).coerceAtLeast(0L) }

    /** 阈值对应的绝对时长。 */
    fun thresholdMs(thresholdDays: Int): Long = thresholdDays.toLong() * MILLIS_PER_DAY

    /** 是否已超期。无锚点恒为「未超期」（无记录不提醒，R2）。 */
    fun isOverdue(anchorMs: Long?, nowMs: Long, thresholdDays: Int): Boolean {
        val elapsed = elapsedMs(anchorMs, nowMs) ?: return false
        return elapsed >= thresholdMs(thresholdDays)
    }

    /** 向下取整的整数天（展示用，如「距上次记录排便 X 天」）；无锚点返回 null。 */
    fun wholeDaysSince(anchorMs: Long?, nowMs: Long): Long? = elapsedMs(anchorMs, nowMs)?.div(MILLIS_PER_DAY)
}
