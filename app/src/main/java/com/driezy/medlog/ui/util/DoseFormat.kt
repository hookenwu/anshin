package com.driezy.medlog.ui.util

import java.math.BigDecimal

/**
 * 将 Double 格式化为剂量显示：整数省略小数点，否则保留 1 位小数。
 * 例：2.0 → "2", 1.5 → "1.5"
 */
fun Double.formatDose(): String = if (this == toLong().toDouble()) {
    toLong().toString()
} else {
    "%.1f".format(this)
}

/**
 * 将 Double 格式化为高精度剂量显示：整数省略小数点，否则保留最多 2 位小数并裁剪尾零。
 * 例：2.0 → "2", 1.5 → "1.5", 0.25 → "0.25"
 *
 * 极小值（如 μg 规格里 < 0.005 的值）不会被两位小数四舍五入吞成 "0"，退回精确的十进制表示，
 * 保证「合计」对微克级剂量也能如实显示。
 */
fun Double.formatDosePrecise(): String = if (this == toLong().toDouble()) {
    toLong().toString()
} else {
    val rounded = "%.2f".format(this).trimEnd('0').trimEnd('.')
    if (rounded != "0" && rounded != "-0") {
        rounded
    } else {
        BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }
}
