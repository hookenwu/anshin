package com.driezy.medlog.capability.reminders

import com.driezy.medlog.data.recipient.ActiveRecipientStore

/**
 * 提醒投影登记项（`alarm_projection_registry` 的 StringSet）的纯集合操作。
 *
 * 单独抽出来是为了能用 JVM 单测直接验证「登记 / 撤销 / 清理」的字符串结果——
 * phantom 登记项正是从这类集合操作里漏掉的：撤销某目标的登记时若只按
 * `(recipientId, id)` 定位，一旦所属行已被删除、成员 id 无从得知，就永远清不掉真实条目。
 *
 * 本对象不碰 Android，只做集合与字符串运算，因此可被普通单测直接覆盖。
 */
internal object AlarmProjectionRegistry {

    /** 解析任意历史形态的登记项：本版三段式 → 阶段 1 两段式 → 阶段 0 裸 id（用药）。 */
    fun parse(entry: String): ReminderTarget? = ReminderTarget.parse(entry)
        ?: ReminderTarget.parseLegacyRecipientKey(entry)
        ?: entry.toLongOrNull()?.let { id ->
            ReminderTarget(ActiveRecipientStore.NO_RECIPIENT, ReminderTargetType.MEDICATION, id)
        }

    /** 解析全部登记项，并去掉解析后重复的目标（旧格式与三段式可能指向同一目标）。 */
    fun parseAll(entries: Set<String>): List<ReminderTarget> = entries.mapNotNull(::parse).distinct()

    /** 登记一个目标；已存在时返回原集合。 */
    fun register(entries: Set<String>, target: ReminderTarget): Set<String> =
        if (entries.contains(target.serialize())) entries else entries + target.serialize()

    /**
     * 撤销一个目标的登记项（成员 id 已知时）。
     *
     * 三段式对所有类型都清；阶段 1 两段式与阶段 0 裸 id 只可能是用药，
     * 只对 [ReminderTargetType.MEDICATION] 清理——否则取消同号的照护事项会误删该成员名下的药物旧登记项。
     * MEDICATION 分支与改造前逐字一致（含短路顺序语义）。
     */
    fun unregister(entries: Set<String>, target: ReminderTarget): Set<String> =
        if (target.type == ReminderTargetType.MEDICATION) {
            entries - setOf(target.serialize(), "${target.recipientId}:${target.id}", target.id.toString())
        } else {
            entries - target.serialize()
        }

    /**
     * 按 `(type, id)` 跨成员匹配登记项（所属行已删除、成员 id 无从得知时使用）。
     *
     * 用药的旧格式（两段式 / 裸 id）解析后同属 MEDICATION，因此一并命中；
     * 照护事项只认本版三段式，不会误伤同号用药。
     */
    fun matchingIdentity(entries: Set<String>, type: ReminderTargetType, id: Long): List<ReminderTarget> =
        parseAll(entries).filter { it.type == type && it.id == id }

    /**
     * 自愈清理：返回所有不再存活（[isLive] 为 false）的目标。
     *
     * 调用方据此取消闹钟并按类型收回登记项；存活目标原样保留，不会误取消应当保留的闹钟。
     */
    fun orphaned(entries: Set<String>, isLive: (ReminderTarget) -> Boolean): List<ReminderTarget> =
        parseAll(entries).filterNot(isLive)
}
