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

/**
 * 「今日计划」标题行是否渲染（docs/tracked-events-spec.md §6）。
 *
 * 有用药计划时必渲染；否则只要有照护事件状态的承载位（[status] 非空，含「无记录」引导态）也渲染——
 * 保证**无计划且零记录**的成员仍有可见的『记录』入口，冷启动也能记下第一条（GAP3）。
 * 仅当既无用药计划、状态又尚未观察（null）时才不渲染（不新增任何区块）。
 */
fun shouldRenderTodayPlanHeader(overallTotal: Int, status: CareEventStatusUi?): Boolean =
    overallTotal > 0 || status != null
