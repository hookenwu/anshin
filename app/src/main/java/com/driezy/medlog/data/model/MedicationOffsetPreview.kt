package com.driezy.medlog.data.model

import java.time.LocalTime

/**
 * 一行实时预览：`早餐后 08:00 → 08:30`。
 *
 * 编辑器与药品详情共用这一份，所以两处显示不可能各算各的。
 */
data class OffsetPreviewRow(val period: TimePeriod, val base: LocalTime, val resolved: LocalTime)

/**
 * 预览 = 把**写路径的唯一物化点** [materializedFor] 跑一遍（每个时段一次，保留"哪一段 → 几点"的
 * 对应关系）。因此：
 *
 * - 预览里**没有**任何偏移算术，也没有第二个 `resolveWithOffset` 调用方；
 * - 看到的钟点就是保存后会落库的钟点（B11 的 `preview == stored` 由构造保证）；
 * - 非法档位在这里抛 [IllegalTimeOffsetException] —— 调用方只会传可选/已存的合法值。
 */
fun previewOffsets(periods: List<TimePeriod>, schedule: RoutineSchedule, offsetMinutes: Int): List<OffsetPreviewRow> =
    periods.mapNotNull { period ->
        val anchor = period.toAnchorOrNull() ?: return@mapNotNull null
        val materialized = Medication(
            name = "",
            doseUnit = "",
            timePeriod = TimePeriods.encode(listOf(period)),
            offsetMinutes = offsetMinutes,
        ).materializedFor(schedule)
        OffsetPreviewRow(
            period = period,
            base = schedule.resolve(anchor),
            resolved = LocalTime.parse(materialized.reminderTimes),
        )
    }

/** 药品详情：从库里的 `timePeriod` 字符串取时段，复用同一份预览。 */
fun previewOffsetsFor(rawTimePeriod: String, schedule: RoutineSchedule, offsetMinutes: Int): List<OffsetPreviewRow> =
    previewOffsets(
        periods = TimePeriods.parse(rawTimePeriod).filter { it != TimePeriod.EXACT },
        schedule = schedule,
        offsetMinutes = offsetMinutes,
    )
