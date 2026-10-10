package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.RoutineTimeSlot
import kotlinx.coroutines.flow.Flow

data class AppearancePreferenceState(
    val themeMode: ThemeMode,
    val useDynamicColor: Boolean,
    val themePaletteName: String,
    val fontMode: FontMode,
    val appTextScale: AppTextScale,
    val uiDensityScale: UiDensityScale,
    val autoCollapseCompletedGroups: Boolean,
    val homeHeroStyle: HomeHeroStyle,
    val medicationSortOrder: MedicationSortOrder,
)

interface AppearancePreferences {
    val appearance: Flow<AppearancePreferenceState>
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun updateUseDynamicColor(enabled: Boolean)
    suspend fun updateThemePalette(paletteName: String)
    suspend fun updateFontMode(fontMode: FontMode)
    suspend fun updateAppTextScale(scale: AppTextScale)
    suspend fun updateUiDensityScale(scale: UiDensityScale)
    suspend fun updateAutoCollapseCompletedGroups(enabled: Boolean)
    suspend fun updateHomeHeroStyle(style: HomeHeroStyle)
    suspend fun updateMedicationSortOrder(order: MedicationSortOrder)
}

data class ReminderPreferenceState(
    val persistentReminder: Boolean,
    val persistentIntervalMinutes: Int,
    val routineSchedule: RoutineSchedule,
    val travelMode: Boolean,
    val homeTimeZoneId: String,
    val earlyReminderMinutes: Int,
    val followUpReminderEnabled: Boolean,
    val followUpDelayMinutes: Int,
    val followUpMaxCount: Int,
)

interface ReminderPreferences {
    val reminders: Flow<ReminderPreferenceState>
    suspend fun updatePersistentReminder(enabled: Boolean)
    suspend fun updatePersistentInterval(minutes: Int)
    suspend fun updateRoutineTime(slot: RoutineTimeSlot, time: RoutineTime)
    suspend fun updateRoutineSchedule(schedule: RoutineSchedule)
    suspend fun updateTravelMode(enabled: Boolean, homeTimeZoneId: String = "")
    suspend fun updateEarlyReminderMinutes(minutes: Int)
    suspend fun updateFollowUpSettings(enabled: Boolean? = null, delayMinutes: Int? = null, maxCount: Int? = null)
}

data class FeaturePreferenceState(
    val enableSymptomDiary: Boolean,
    val enableDrugInteractionCheck: Boolean,
    val enableDrugDatabase: Boolean,
    val enableHealthModule: Boolean,
    val enableTimePeriodMode: Boolean,
)

interface FeaturePreferences {
    val features: Flow<FeaturePreferenceState>
    suspend fun updateFeatureFlags(
        enableSymptomDiary: Boolean? = null,
        enableDrugInteraction: Boolean? = null,
        enableDrugDatabase: Boolean? = null,
        enableHealthModule: Boolean? = null,
        enableTimePeriodMode: Boolean? = null,
    )
}

data class AiPreferenceState(
    val ocrModelType: OcrModelType,
    val cloudAiEnabled: Boolean,
    val cloudAiImageAnalysisEnabled: Boolean,
    val cloudAiHealthInsightsEnabled: Boolean,
    val cloudAiWifiOnly: Boolean,
    val cloudAiProvider: CloudAiProvider,
    val cloudAiModel: String,
    val mimoCloudAiBaseUrl: String,
    val anthropicCloudAiBaseUrl: String,
    val openAiCompatibleBaseUrl: String,
    val openAiCompatibleAuthMode: OpenAiCompatibleCloudAuthMode,
    val openAiCompatibleProviderName: String,
)

interface AiPreferences {
    val ai: Flow<AiPreferenceState>
    suspend fun updateOcrModelType(modelType: OcrModelType)
    suspend fun updateCloudAiSettings(
        enabled: Boolean? = null,
        imageAnalysisEnabled: Boolean? = null,
        healthInsightsEnabled: Boolean? = null,
        wifiOnly: Boolean? = null,
        provider: CloudAiProvider? = null,
        model: String? = null,
        mimoBaseUrl: String? = null,
        anthropicBaseUrl: String? = null,
        openAiCompatibleBaseUrl: String? = null,
        openAiCompatibleAuthMode: OpenAiCompatibleCloudAuthMode? = null,
        openAiCompatibleProviderName: String? = null,
    )
}

data class WidgetPreferenceState(
    val showActions: Boolean,
    val themeMode: WidgetThemeMode,
    val colorSource: WidgetColorSource,
    val paletteName: String,
    val densityScale: WidgetDensityScale,
    val textScale: WidgetTextScale,
)

interface WidgetPreferences {
    val widgets: Flow<WidgetPreferenceState>
    suspend fun updateWidgetShowActions(enabled: Boolean)
    suspend fun updateWidgetAppearance(
        themeMode: WidgetThemeMode? = null,
        colorSource: WidgetColorSource? = null,
        paletteName: String? = null,
        densityScale: WidgetDensityScale? = null,
        textScale: WidgetTextScale? = null,
    )
}

/**
 * 照护事件提醒偏好（docs/tracked-events-spec.md §4 D3 / §5）。
 *
 * 键一律按 **(成员, kind)** 分片：`care_event_reminder_enabled#<recipientId>#<kind>`、
 * `care_event_reminder_threshold_days#<recipientId>#<kind>`、`care_event_nudged_day#<recipientId>#<kind>`。
 * 落在既有成员级 DataStore 偏好层——**不建表**、无类型管理 UI；`kind` 恒为代码常量（首期 `BOWEL`）。
 * 缺省：开关 **false**、阈值 **3**、日标记 **缺省（从未提醒）**。
 */
interface CareEventReminderPreferences {
    suspend fun isEnabled(recipientId: Long, kind: String): Boolean
    suspend fun setEnabled(recipientId: Long, kind: String, enabled: Boolean)

    suspend fun thresholdDays(recipientId: Long, kind: String): Int
    suspend fun setThresholdDays(recipientId: Long, kind: String, days: Int)

    /** 上次提醒的设备本地 epochDay；从未提醒返回 null。 */
    suspend fun nudgedDay(recipientId: Long, kind: String): Long?
    suspend fun setNudgedDay(recipientId: Long, kind: String, epochDay: Long)

    /** 成员删除时清理其所有 kind 的偏好键（R8）。 */
    suspend fun clearForRecipient(recipientId: Long)

    /** 响应式读取（设置页控件用）：开关 + 阈值。 */
    fun reminderSetting(recipientId: Long, kind: String): Flow<CareEventReminderSetting>
}

/** 设置页显示的照护事件提醒设置（纯数据）。 */
data class CareEventReminderSetting(val enabled: Boolean = false, val thresholdDays: Int = 3)
