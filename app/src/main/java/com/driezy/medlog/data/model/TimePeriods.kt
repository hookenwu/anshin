package com.driezy.medlog.data.model

/**
 * `medications.timePeriod` 列的编解码。
 *
 * 该列历史上只存一个时段 key（如 `afterBreakfast`）；为了支持「同一药品挂多个用餐时段」，
 * 现在它是一组逗号分隔的 key。单值写法继续合法，因此**旧数据不需要迁移**。
 *
 * 归一化规则：
 *  - 出现 [TimePeriod.EXACT] 的项直接丢弃（exact 表示"没有作息锚点"，与具体时段互斥）；
 *  - 无法识别的 key 忽略，不做降级猜测；
 *  - 去重并保持声明顺序；
 *  - 归一化后为空 → 等价于 `exact`。
 */
object TimePeriods {

    private const val SEPARATOR = ","

    fun parse(raw: String?): List<TimePeriod> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { key -> TimePeriod.entries.firstOrNull { it.key == key } }
            .filter { it != TimePeriod.EXACT }
            .distinct()
    }

    /** 归一化后写回 `timePeriod` 列；空集合按 `exact` 存。 */
    fun encode(periods: Collection<TimePeriod>): String {
        val keys = periods.filter { it != TimePeriod.EXACT }.distinct().map { it.key }
        return if (keys.isEmpty()) TimePeriod.EXACT.key else keys.joinToString(SEPARATOR)
    }

    /** 精确时间模式（没有任何作息时段）。 */
    fun isExact(raw: String?): Boolean = parse(raw).isEmpty()

    fun contains(raw: String?, period: TimePeriod): Boolean = period in parse(raw)
}
