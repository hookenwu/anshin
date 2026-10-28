package com.driezy.medlog.util

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.resolve
import com.driezy.medlog.data.model.toAnchorOrNull
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.routineSchedule

/**
 * 纯函数工具：将服药时段 + 用户作息偏好 转换为 HH:mm 提醒时间字符串。
 *
 * 提取自 AddMedicationViewModel，便于在 ResyncRemindersUseCase、
 * BootReceiver 等场景中复用，无需依赖 ViewModel 实例。
 *
 * **薄委托**：时段 → 钟点的权威只有 `RoutineSchedule.resolve`（＝
 * `floorMod(resolveUnwrapped(anchor), 1440)`）。本类不再自带第二套算术
 * （旧的 `adjustTime` 已删除），也**不接收偏移参数**——药品侧的偏移由写路径的唯一物化点
 * `Medication.materializedFor` 负责，因此这里结构上不可能二次加偏移
 * （docs/medication-time-offset.md §5.0、§8 第 25 条）。
 */
object ReminderTimeUtils {

    /**
     * 根据 [period] 和用户当前的 [prefs] 计算对应的 HH:mm 字符串。
     *
     * 注意：[TimePeriod.EXACT] 需由调用方自行维护，此处返回空字符串占位。
     */
    fun timePeriodToReminderTime(period: TimePeriod, prefs: SettingsPreferences): String =
        timePeriodToReminderTime(period, prefs.routineSchedule())

    /** 与 [RoutineSchedule.resolve] 逐字等价；`EXACT` 没有作息锚点 ⇒ 空串。 */
    fun timePeriodToReminderTime(period: TimePeriod, schedule: RoutineSchedule): String {
        val anchor = period.toAnchorOrNull() ?: return ""
        val time = schedule.resolve(anchor)
        return "%02d:%02d".format(time.hour, time.minute)
    }
}
