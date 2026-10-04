package com.driezy.medlog.feature.medications.editor

/**
 * 剂量单位候选项。
 *
 * 片/粒/滴/袋/支/贴 依语言本地化，`ml`/`mg`/`g` 是无语言差异的计量单位，直接内联。
 * 抽成纯函数而不是写在 composable 里，是为了让「单位档位是否齐全」这件事能被 JVM 单测锁住——
 * 少一个档位（例如过去的克）就是用户可见的功能缺失。
 */
internal fun doseUnitOptions(
    tablet: String,
    capsule: String,
    drop: String,
    bag: String,
    tube: String,
    patch: String,
): List<String> = listOf(tablet, capsule, "ml", "mg", "g", drop, bag, tube, patch)
