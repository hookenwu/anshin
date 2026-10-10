package com.driezy.medlog.capability.reminders

/**
 * 提醒的展示种类：用药三态 + 照护事项。
 *
 * 只描述「要展示什么」，不含任何 Android 类型，便于 JVM 单测。
 */
enum class ReminderKind {
    /** 正式服药时间到。 */
    MEDICATION_NORMAL,

    /** 提前预告（尚未到正式服药时间）。 */
    MEDICATION_EARLY,

    /** 漏服再提醒。 */
    MEDICATION_FOLLOW_UP,

    /** 照护事项提醒。 */
    CARE_TASK,
}

/**
 * 类型化派发决策（T3）：把闹钟 intent 的 extras 解析成接收器要走的通路。
 *
 * 接收器只负责取值、调用 [decide]、再按 [kind] 做 I/O；目标类型识别、旧格式兜底、
 * 用药三态的优先级等分支全部集中在此，由 JVM 单测覆盖。
 */
data class ReminderDispatch(
    /** 目标类型（用药 / 照护事项）。 */
    val targetType: ReminderTargetType,
    /** 目标实体 id（用药 = medicationId，照护事项 = careTaskId）。 */
    val targetId: Long,
    /** 要展示的提醒种类。 */
    val kind: ReminderKind,
) {
    companion object {
        /** [targetId]/[medicationId] 缺失时的哨兵值（与 `Intent.getLongExtra` 的默认值一致）。 */
        const val NO_TARGET_ID = -1L

        /**
         * 解析类型化提醒目标与展示种类。
         *
         * - [targetTypeKey] 缺失或无法识别 → 一律按 [ReminderTargetType.MEDICATION] 处理：
         *   旧版安装包排出的闹钟只带 `EXTRA_MED_ID`，升级后必须照常触发（硬要求，不是锦上添花）。
         * - [targetId] 缺失（<= 0）时，用药退回 [medicationId]；照护事项无 id 可退，视为无效。
         *
         * @return 无法定位目标（最终 id <= 0）时返回 null，调用方直接忽略该 intent。
         */
        fun decide(
            targetTypeKey: String?,
            targetId: Long,
            medicationId: Long,
            isEarly: Boolean,
            isFollowUp: Boolean,
        ): ReminderDispatch? {
            val type = targetTypeKey?.let { key -> ReminderTargetType.fromKey(key) }
                ?: ReminderTargetType.MEDICATION
            return when (type) {
                ReminderTargetType.MEDICATION -> {
                    // 新版闹钟带 EXTRA_TARGET_ID；旧版只有 EXTRA_MED_ID，退回后者
                    val id = if (targetId > 0) targetId else medicationId
                    if (id <= 0) {
                        null
                    } else {
                        ReminderDispatch(
                            targetType = ReminderTargetType.MEDICATION,
                            targetId = id,
                            kind = medicationKind(isEarly, isFollowUp),
                        )
                    }
                }
                ReminderTargetType.CARE_TASK -> {
                    if (targetId <= 0) {
                        null
                    } else {
                        ReminderDispatch(
                            targetType = ReminderTargetType.CARE_TASK,
                            targetId = targetId,
                            kind = ReminderKind.CARE_TASK,
                        )
                    }
                }
                // 照护事件不排闹钟，绝不会出现在闹钟 intent 里；出现即忽略。
                ReminderTargetType.CARE_EVENT -> null
            }
        }

        /** 用药三态：提前预告优先于漏服再提醒，两者都不是则为正式提醒。 */
        private fun medicationKind(isEarly: Boolean, isFollowUp: Boolean): ReminderKind = when {
            isEarly -> ReminderKind.MEDICATION_EARLY
            isFollowUp -> ReminderKind.MEDICATION_FOLLOW_UP
            else -> ReminderKind.MEDICATION_NORMAL
        }
    }
}
