package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.ui.util.canScaleDoseStrength
import com.driezy.medlog.ui.util.formatDosePrecise

/** 累计用量（值 + 单位）。 */
data class AccumulatedDose(val value: Double, val unit: String) {
    /** 展示文本，如 `0.5 g` / `4 粒`。 */
    fun formatted(): String = "${value.formatDosePrecise()} $unit"
}

/**
 * 累计用量 = Σ(TAKEN 日志的实际剂量)。
 *
 * - 有规格且单位可换算：乘以 [Medication.doseStrength]，单位取 [Medication.doseStrengthUnit]；
 * - 无规格（或规格与剂量单位不可换算）：退化为「累计 N <doseUnit>」，与改造前口径一致。
 *
 * PARTIAL/SKIPPED 不计入，避免与「已服次数」口径混淆（依从率仍按状态计数，不按量加权）。
 */
fun accumulatedDoseUsage(medication: Medication, logs: List<MedicationLog>): AccumulatedDose {
    val takenQuantity = logs
        .filter { it.medicationId == medication.id && it.status == LogStatus.TAKEN }
        .sumOf { it.actualDoseQuantity ?: medication.doseQuantity }
    val strength = medication.doseStrength
    val strengthUnit = medication.doseStrengthUnit
    val hasStrength = strength != null && !strengthUnit.isNullOrBlank()
    val convertible = hasStrength && canScaleDoseStrength(strengthUnit ?: "", medication.doseUnit)
    return if (convertible) {
        AccumulatedDose(takenQuantity * (strength ?: 0.0), strengthUnit.orEmpty())
    } else {
        AccumulatedDose(takenQuantity, medication.doseUnit)
    }
}
