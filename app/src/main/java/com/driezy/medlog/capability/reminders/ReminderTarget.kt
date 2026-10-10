package com.driezy.medlog.capability.reminders

/**
 * 提醒目标：整条提醒链路（registry 登记项、PendingIntent 编号、接收器文案与动作）的目标标识。
 *
 * 改造前只认药物（`REGISTERED_MEDICATION_IDS` + `EXTRA_MED_ID`）；照护事项加入后，
 * 目标是 `(recipientId, type, id)` 三元组。序列化形式：
 *
 * - 本版：`<recipientId>:<type>:<id>`，如 `2:med:13` / `2:task:5`
 * - 阶段 1 旧格式：`<recipientId>:<id>`（无类型段，只可能是药物）
 * - 更早旧格式：`<id>`（无成员前缀）
 *
 * 后两种都由重排流程作废并重建，因此不需要数据迁移（见 `AlarmScheduler.retireLegacyProjections`）。
 */
data class ReminderTarget(val recipientId: Long, val type: ReminderTargetType, val id: Long) {
    /** 登记项序列化：`<recipientId>:<type>:<id>`。 */
    fun serialize(): String = "$recipientId:${type.key}:$id"

    /** 该目标第 [slotIndex] 个时间槽的 requestCode（用药沿用改造前的取值）。 */
    fun slotRequestCode(slotIndex: Int): Int = type.codeBase + (id * SLOT_STRIDE).toInt() + slotIndex

    fun earlyReminderRequestCode(slotIndex: Int): Int = slotRequestCode(slotIndex) + EARLY_REMINDER_CODE_OFFSET

    fun followUpRequestCode(slotIndex: Int): Int = slotRequestCode(slotIndex) + FOLLOW_UP_CODE_OFFSET

    companion object {
        /** 单个目标可占用的 requestCode 数量（100 个时间槽）。 */
        const val SLOT_STRIDE = 100

        const val EARLY_REMINDER_CODE_OFFSET = 50_000

        const val FOLLOW_UP_CODE_OFFSET = 100_000

        /**
         * 解析登记项。不认识的形式（含旧格式）返回 null，由调用方走"作废重建"路径。
         */
        fun parse(raw: String): ReminderTarget? {
            val parts = raw.split(':')
            if (parts.size != 3) return null
            val recipientId = parts[0].toLongOrNull() ?: return null
            val type = ReminderTargetType.fromKey(parts[1]) ?: return null
            val id = parts[2].toLongOrNull() ?: return null
            if (recipientId < 0 || id < 0) return null
            return ReminderTarget(recipientId, type, id)
        }

        /** 旧格式：阶段 1 的 `<recipientId>:<medId>`（只可能是药物）。 */
        fun parseLegacyRecipientKey(raw: String): ReminderTarget? {
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val recipientId = parts[0].toLongOrNull() ?: return null
            val id = parts[1].toLongOrNull() ?: return null
            if (recipientId < 0 || id < 0) return null
            return ReminderTarget(recipientId, ReminderTargetType.MEDICATION, id)
        }
    }
}

/** 提醒目标类型与其 requestCode 编号空间。 */
enum class ReminderTargetType(val key: String, val codeBase: Int) {
    /** 用药：沿用改造前的 `id * 100 + slot`，基数 0。 */
    MEDICATION("med", 0),

    /** 照护事项：独立基数，避免与用药撞码。 */
    CARE_TASK("task", CARE_TASK_CODE_BASE),

    /** 照护事件（排便）缺席型每日提醒：再取一个独立基数，与用药/照护事项互不撞码。 */
    CARE_EVENT("care_event", CARE_EVENT_CODE_BASE),

    ;

    companion object {
        fun fromKey(key: String): ReminderTargetType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 照护事项 requestCode 基数。
 *
 * 用药沿用 `id * 100 + slot`，再加提前预告（+50_000）与漏服再提醒（+100_000）两个偏移，
 * 即单个药物的编号上限为 `id * 100 + 150_000`。取 1e8 作照护事项的基数后，只要两类实体的
 * id 都小于约 99.8 万（本地自增，量级完全满足），用药区间就严格小于照护区间、不会撞码——
 * 由 `ReminderTargetTest` 断言。
 */
const val CARE_TASK_CODE_BASE = 100_000_000

/**
 * 照护事件（排便）提醒 requestCode 基数。照护事项上限约 1e8 + 90万*100 ≈ 1.9e8；
 * 取 2e8 作照护事件的基数后，两类互不重叠。由 `ReminderTargetTest` 覆盖。
 */
const val CARE_EVENT_CODE_BASE = 200_000_000
