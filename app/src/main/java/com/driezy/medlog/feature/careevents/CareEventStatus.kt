package com.driezy.medlog.feature.careevents

import com.driezy.medlog.domain.CareEventInterval

/**
 * 今日页「今日计划」标题行里的排便状态（docs/tracked-events-spec.md §6）。
 *
 * 折入既有标题行的同一条 `Row`/该 item 内，**不新增卡片、不新增底部 Tab**。
 * 它是**次级信息**：真机小屏/字体放大/超长药品名下允许换行、截断或隐藏，**用药信息优先**。
 */
data class CareEventStatusUi(
    /** 距上次记录的天数（整数，向下取整）；无记录为 null。 */
    val daysSince: Long?,
    /** 是否有过任何排便记录。无记录时状态行退化为引导文案。 */
    val hasAnyRecord: Boolean,
)

/** 由锚点与当前时刻派生状态（纯函数，便于 JVM 单测）。 */
fun buildCareEventStatus(anchorMs: Long?, nowMs: Long): CareEventStatusUi = CareEventStatusUi(
    daysSince = CareEventInterval.wholeDaysSince(anchorMs, nowMs),
    hasAnyRecord = anchorMs != null,
)
