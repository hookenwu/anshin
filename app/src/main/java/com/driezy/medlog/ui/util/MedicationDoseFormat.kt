package com.driezy.medlog.ui.util

import com.driezy.medlog.data.model.Medication

/**
 * 规格（单粒强度）展示口径。
 *
 * 语义边界（见 docs/dose-strength.md）：
 * - `doseQuantity` + `doseUnit` = 每次服用数量及其单位（也是库存计量单位），语义不变；
 * - `doseStrength` + `doseStrengthUnit` = 每 1 个 `doseUnit` 的规格（如 0.25 g/粒）；
 * - 合计 = `doseStrength × doseQuantity`，仅在「规格单位与剂量单位处于同一计量口径」时有意义。
 */
enum class DoseUnitFamily { MASS, VOLUME, COUNT }

/** mg/g 归质量，ml 归体积，其余（片/粒/滴/袋/支/贴 及自定义单位）按计数处理。 */
fun doseUnitFamily(unit: String): DoseUnitFamily = when (unit.trim().lowercase()) {
    "mg", "g" -> DoseUnitFamily.MASS
    "ml" -> DoseUnitFamily.VOLUME
    else -> DoseUnitFamily.COUNT
}

/**
 * 规格单位与剂量单位能否相乘求和。
 *
 * 计数型剂量单位（片/粒/滴/袋/支/贴）总可以把「每份规格」乘以份数；
 * 计量型剂量单位（mg/g/ml）必须与规格单位同族，否则不做求和（如质量规格配体积剂量）。
 */
fun canScaleDoseStrength(strengthUnit: String, doseUnit: String): Boolean {
    val strengthFamily = doseUnitFamily(strengthUnit)
    val doseFamily = doseUnitFamily(doseUnit)
    return doseFamily == DoseUnitFamily.COUNT || strengthFamily == doseFamily
}

/** 规格 × 次数 文本，如 `0.25g × 2粒`。 */
fun formatStrengthPair(strength: Double, strengthUnit: String, quantity: Double, doseUnit: String): String =
    "${strength.formatDosePrecise()}$strengthUnit × ${quantity.formatDose()}$doseUnit"

/** 一剂展示：有规格 `0.25g × 2粒`，无规格保持 `2 粒`。 */
fun Medication.doseDisplayText(): String {
    val strength = doseStrength
    val strengthUnit = doseStrengthUnit
    return if (strength != null && !strengthUnit.isNullOrBlank()) {
        formatStrengthPair(strength, strengthUnit, doseQuantity, doseUnit)
    } else {
        "${doseQuantity.formatDose()} $doseUnit"
    }
}

/** 单次合计文本（规格 × 次数），无规格或单位不可换算时返回 null，如 `0.5g`。 */
fun Medication.perDoseTotalText(): String? {
    val strength = doseStrength ?: return null
    val strengthUnit = doseStrengthUnit ?: return null
    if (strengthUnit.isBlank() || !canScaleDoseStrength(strengthUnit, doseUnit)) return null
    return "${(strength * doseQuantity).formatDosePrecise()}$strengthUnit"
}
