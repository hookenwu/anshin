package com.driezy.medlog.feature.medications.list

import com.driezy.medlog.R
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.TimePeriods

/**
 * 「我的药品」按时段分组的组（docs/medication-grouping.md D2）。
 *
 * 枚举声明顺序 = 渲染顺序（早 → 午 → 晚 → 睡前 → 无固定时段），所以调用方不需要再排序，
 * 也不能依赖 `entries` 之外的顺序来源。
 */
internal enum class DosePeriodGroup(val labelRes: Int) {
    MORNING(R.string.medication_group_morning),
    NOON(R.string.medication_group_noon),
    EVENING(R.string.medication_group_evening),
    BEDTIME(R.string.medication_group_bedtime),
    WITHOUT_PERIOD(R.string.medication_group_without_period),
}

/** 一个非空组。`medications` 是**同一批实体**，不是拷贝（D4）。 */
internal data class MedicationPeriodGroup(val group: DosePeriodGroup, val medications: List<Medication>)

/** `TimePeriod` → 组；`EXACT` 不属于任何组（它表示「没有作息锚点」）。 */
internal fun TimePeriod.dosePeriodGroupOrNull(): DosePeriodGroup? = when (this) {
    TimePeriod.BEFORE_BREAKFAST, TimePeriod.MORNING, TimePeriod.AFTER_BREAKFAST -> DosePeriodGroup.MORNING
    TimePeriod.BEFORE_LUNCH, TimePeriod.AFTER_LUNCH, TimePeriod.AFTERNOON -> DosePeriodGroup.NOON
    TimePeriod.BEFORE_DINNER, TimePeriod.AFTER_DINNER, TimePeriod.EVENING -> DosePeriodGroup.EVENING
    TimePeriod.BEDTIME -> DosePeriodGroup.BEDTIME
    TimePeriod.EXACT -> null
}

/**
 * 该药命中的组（D1）。与 `toDomainSchedule()` 同优先级、同归一化：
 * PRN / 间隔给药的钟点不挂在作息锚点上，因此即使 `timePeriod` 里残留了时段也不参与分组；
 * 其余按 `TimePeriods.parse` 归一化后的时段映射，归一化结果为空则落在「无固定时段」。
 *
 * 永不返回空列表：没有时段的药也一定进 [DosePeriodGroup.WITHOUT_PERIOD]，不存在被静默丢弃的行。
 */
internal fun Medication.dosePeriodGroups(): List<DosePeriodGroup> {
    val periods = if (isPRN || intervalHours > 0) emptyList() else TimePeriods.parse(timePeriod)
    return periods.mapNotNull { it.dosePeriodGroupOrNull() }.distinct()
        .ifEmpty { listOf(DosePeriodGroup.WITHOUT_PERIOD) }
}

/**
 * 纯函数：把**已过滤、已排序**的列表按时段分组。
 *
 * 只有一个来源 `timePeriod`，不查库、不做 schema 变更。只返回非空组，组内保持输入顺序
 * （所以现有的排序档在组内继续生效）。多时段药会出现在它命中的每个组里（D4）——
 * 这不算「多一条记录」，行仍然是同一个实体。
 */
internal fun List<Medication>.groupedByDosePeriod(): List<MedicationPeriodGroup> =
    DosePeriodGroup.entries.mapNotNull { group ->
        val medications = filter { group in it.dosePeriodGroups() }
        if (medications.isEmpty()) null else MedicationPeriodGroup(group, medications)
    }

/**
 * 选择集的唯一写入点（D4）：`Set<Long>` 结构上不可能出现同一个 id 两次，
 * 因此同一药在两个组里各被点一次也只会有一个 id，`已选 N 项` 数的是药品而不是行。
 */
internal fun toggledSelection(current: Set<Long>, id: Long): Set<Long> =
    if (id in current) current - id else current + id
