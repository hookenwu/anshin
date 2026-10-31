package com.driezy.medlog.feature.medications.editor

import com.driezy.medlog.data.model.OffsetPreviewRow
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.previewOffsets
import com.driezy.medlog.data.model.toAnchorOrNull
import com.driezy.medlog.data.model.validateTimeOffset
import com.driezy.medlog.domain.model.RoutineAnchor

/** 步进器档位：-60..+60，步长 15（含 0，共 9 档）。 */
const val TIME_OFFSET_STEP_MINUTES: Int = 15
const val TIME_OFFSET_MAX_MINUTES: Int = 60

val TIME_OFFSET_SLOTS: List<Int> =
    (-TIME_OFFSET_MAX_MINUTES..TIME_OFFSET_MAX_MINUTES step TIME_OFFSET_STEP_MINUTES).toList()

/**
 * 「可用，但这一档为什么不可选」——只在 `enabled == false` 时携带，页面必须把原因**显示出来**
 * （docs/medication-time-offset.md §5.1；不是 tooltip、不是静默隐藏）。
 */
sealed interface OffsetUnavailable {
    /** 该时段基线 + 这一档会跨过零点（多时段取交集：指明是哪一段）。 */
    data class CrossMidnight(val period: TimePeriod, val minutes: Int) : OffsetUnavailable

    /** 该时段基线本身已回绕（跨日）；整条步进器都不可用。 */
    data class WrappedAnchor(val period: TimePeriod) : OffsetUnavailable
}

data class OffsetOption(val minutes: Int, val enabled: Boolean, val unavailable: OffsetUnavailable? = null)

/** 一行实时预览：`早餐后 08:00 → 08:30`（[OffsetPreviewRow] 与详情页共用）。 */

/**
 * 编辑器步进器的**全部**状态：9 档、哪些档可用/为什么不可用、整条是否禁用、已存值是否非法、
 * 以及预览。纯函数产物，JVM 可直接断言。
 */
data class OffsetStepperModel(
    val options: List<OffsetOption>,
    val selectedMinutes: Int,
    /** 库里已有的偏移在当前作息下非法 ⇒ 错误态 + 阻止保存（不静默归零）。 */
    val storedRejected: Boolean,
    /** 任一时段基线已回绕 ⇒ 整条步进器禁用（不是单档）。 */
    val wrappedAnchor: TimePeriod?,
    val preview: List<OffsetPreviewRow>,
) {
    val stepperEnabled: Boolean get() = wrappedAnchor == null
    val canSave: Boolean get() = !storedRejected
}

/**
 * 唯一的步进器模型构造点（docs/medication-time-offset.md §5.1）：
 * 校验只调 [validateTimeOffset]，预览只调 [previewOffsets]（它内部走
 * [RoutineSchedule.resolveWithOffset]，全仓唯一的偏移加法）。这里**没有**任何偏移算术，
 * 也**没有**钳位：越界档位保持原值并置灰。
 */
fun offsetStepperModel(periods: Set<TimePeriod>, schedule: RoutineSchedule, storedMinutes: Int): OffsetStepperModel {
    val ordered = periods.sortedBy { it.ordinal }.filter { it != TimePeriod.EXACT }
    val anchors = ordered.mapNotNull { it.toAnchorOrNull() }

    val options = TIME_OFFSET_SLOTS.map { minutes ->
        when (val check = validateTimeOffset(minutes, schedule, anchors, applicable = true)) {
            is TimeOffsetCheck.Allowed -> OffsetOption(minutes = minutes, enabled = true)
            is TimeOffsetCheck.Rejected -> OffsetOption(
                minutes = minutes,
                enabled = false,
                unavailable = check.unavailableFor(ordered),
            )
        }
    }

    val wrapped = options.firstNotNullOfOrNull { option ->
        (option.unavailable as? OffsetUnavailable.WrappedAnchor)?.period
    }
    val storedCheck = validateTimeOffset(storedMinutes, schedule, anchors, applicable = true)
    val storedRejected = storedCheck is TimeOffsetCheck.Rejected
    val preview = if (storedRejected) {
        emptyList()
    } else {
        previewOffsets(ordered, schedule, storedMinutes)
    }

    return OffsetStepperModel(
        options = options,
        selectedMinutes = storedMinutes,
        storedRejected = storedRejected,
        wrappedAnchor = wrapped,
        preview = preview,
    )
}

/** 把校验里的具名锚点映射回时段（同名 1:1），供 UI 指名是哪一段。 */
private fun TimeOffsetCheck.Rejected.unavailableFor(periods: List<TimePeriod>): OffsetUnavailable? {
    val period = anchor?.let { anchor -> periods.firstOrNull { it.name == anchor.name } } ?: return null
    return when (reason) {
        TimeOffsetCheck.Reason.CROSS_MIDNIGHT -> OffsetUnavailable.CrossMidnight(period, minutes)
        TimeOffsetCheck.Reason.WRAPPED_ANCHOR -> OffsetUnavailable.WrappedAnchor(period)
        else -> null
    }
}

/** 仅用于测试/文案的锚点 → 时段映射（同名 1:1，除 EXACT）。 */
internal fun RoutineAnchor.asTimePeriod(): TimePeriod? = TimePeriod.entries.firstOrNull { it.name == name }
