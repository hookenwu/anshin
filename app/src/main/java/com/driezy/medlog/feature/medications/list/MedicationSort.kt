package com.driezy.medlog.feature.medications.list

import com.driezy.medlog.R
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.earliestScheduledTime
import com.driezy.medlog.data.repository.MedicationSortOrder

/** 排序档位在 UI 上的文案；枚举本体留在 core 模块，文案映射留在有 R 的 app 模块。 */
internal val MedicationSortOrder.labelRes: Int
    get() = when (this) {
        MedicationSortOrder.DEFAULT -> R.string.medication_sort_default
        MedicationSortOrder.TIME_ASC -> R.string.medication_sort_time_asc
        MedicationSortOrder.TIME_DESC -> R.string.medication_sort_time_desc
    }

/**
 * 「我的药品」列表排序。
 *
 * [MedicationSortOrder.DEFAULT] 原样返回 DAO 的顺序（高优先级 → 名称），保证默认观感与改造前逐行一致；
 * 时间档按一天中最早的服药时间排序，时间相同再按名称，保证顺序稳定可复现。
 */
internal fun List<Medication>.sortedFor(order: MedicationSortOrder): List<Medication> = when (order) {
    MedicationSortOrder.DEFAULT -> this
    MedicationSortOrder.TIME_ASC -> sortedWith(
        compareBy({ it.earliestScheduledTime() }, { it.name }),
    )

    MedicationSortOrder.TIME_DESC -> sortedWith(
        compareByDescending<Medication> { it.earliestScheduledTime() }.thenBy { it.name },
    )
}
