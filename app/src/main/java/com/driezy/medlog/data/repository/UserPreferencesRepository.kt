package com.driezy.medlog.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.driezy.medlog.data.local.settingsDataStore
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.RoutineTimeSlot
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.feature.onboarding.model.OnboardingDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore 文件名 */
// 已移至 data/local/SettingsDataStore.kt 统一管理

/** 用户偏好设置数据容器 */
data class SettingsPreferences(
    val persistentReminder: Boolean = false,
    val persistentIntervalMinutes: Int = 5,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    val breakfastHour: Int = 8,
    val breakfastMinute: Int = 0,
    val lunchHour: Int = 12,
    val lunchMinute: Int = 0,
    val dinnerHour: Int = 18,
    val dinnerMinute: Int = 0,
    val bedHour: Int = 22,
    val bedMinute: Int = 0,
    /** 是否已完成欢迎引导（首次启动标志） */
    val hasSeenWelcome: Boolean = false,
    /**
     * 旅行模式：开启后考虑按「家乡时区」计算提醒时间。
     */
    val travelMode: Boolean = false,
    /** 家乡时区 ID（如 "Asia/Shanghai"）。空串表示使用内容是设备默认时区。 */
    val homeTimeZoneId: String = "",

    // ── 可选功能开关 ───────────────────────────────────────────────────────────
    /** 是否启用身心记录（底部导航显示「日记」Tab） */
    val enableSymptomDiary: Boolean = true,
    /** 是否启用药品相互作用检测（首页横幅 + 实时检测） */
    val enableDrugInteractionCheck: Boolean = true,
    /** 是否启用药品数据库浏览（底部导航显示「药品」Tab） */
    val enableDrugDatabase: Boolean = true,
    /** 是否启用健康体征模块（底部导航显示「健康」Tab） */
    val enableHealthModule: Boolean = true,
    /** 是否启用作息时间段模式（关闭后添加药品时只显示精确时间选择器） */
    val enableTimePeriodMode: Boolean = true,

    // ── 外观偏好 ──────────────────────────────────────────────────────────────
    /** 深色/浅色/跟随系统 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否使用 Material You 动态颜色（Android 12+ 才生效） */
    val useDynamicColor: Boolean = false,
    /** 主题配色方案名称。实际色板定义在 UI theme 层。 */
    val themePaletteName: String = "ANSHIN",
    /** 字体模式。默认 SYSTEM，尊重系统字体设置。 */
    val fontMode: FontMode = FontMode.SYSTEM,
    /** 应用内文字大小缩放，叠加在系统字体大小之上。 */
    val appTextScale: AppTextScale = AppTextScale.STANDARD,
    /** 应用内元素密度缩放，影响 dp 尺寸。 */
    val uiDensityScale: UiDensityScale = UiDensityScale.STANDARD,

    // ── 今日页面显示偏好 ───────────────────────────────────────────────────────
    /** 已全部服用的时段默认折叠，节省屏幕空间 */
    val autoCollapseCompletedGroups: Boolean = true,
    /** 今日页焦点区域样式；默认使用最直接的行动型。 */
    val homeHeroStyle: HomeHeroStyle = HomeHeroStyle.ACTION,
    val medicationSortOrder: MedicationSortOrder = MedicationSortOrder.DEFAULT,
    // ── 提醒弹性设置 ───────────────────────────────────────────
    /**
     * 提前 N 分钟发送预告提醒。
     * 0 = 关闭（不发预告）；15 / 30 / 60 = 提前对应分钟数发送
     */
    val earlyReminderMinutes: Int = 0,
    // ── 小组件显示偏好 ──────────────────────────────────────────────────────────
    /**
     * 小组件节点是否显示可交互服药被按按钮（true）还是仅显示待服状态指示（false）。
     * true = 操作模式（默认）；false = 状态模式
     */
    val widgetShowActions: Boolean = true,
    /** 小组件明暗主题，独立于主程序主题。 */
    val widgetThemeMode: WidgetThemeMode = WidgetThemeMode.SYSTEM,
    /** 小组件配色来源。 */
    val widgetColorSource: WidgetColorSource = WidgetColorSource.SYSTEM_DYNAMIC,
    /** 小组件独立色板名称，仅在 [WidgetColorSource.CUSTOM_PALETTE] 下生效。 */
    val widgetPaletteName: String = "ANSHIN",
    /** 小组件布局密度，影响 padding/间距/图标和按钮尺寸。 */
    val widgetDensityScale: WidgetDensityScale = WidgetDensityScale.STANDARD,
    /** 小组件文字大小。 */
    val widgetTextScale: WidgetTextScale = WidgetTextScale.STANDARD,
    // ── 漏服再提醒 ──────────────────────────────────────────────────────────────
    val followUpReminderEnabled: Boolean = false,
    val followUpDelayMinutes: Int = 15,
    val followUpMaxCount: Int = 1,

    // ── 健康模块 ──────────────────────────────────────────────────────────────
    /** 用户身高（cm），用于 BMI 计算；0 表示未设置 */
    val userHeightCm: Float = 0f,

    // ── OCR 模型选择 ────────────────────────────────────────────────────────────
    val ocrModelType: OcrModelType = OcrModelType.LIGHT_SVTR,

    // ── 云端 AI 设置 ───────────────────────────────────────────────────────────
    /** 总开关，默认关闭；每个功能还需要单独 opt-in。 */
    val cloudAiEnabled: Boolean = false,
    val cloudAiImageAnalysisEnabled: Boolean = false,
    val cloudAiHealthInsightsEnabled: Boolean = false,
    /** 默认仅 Wi-Fi 上传图片/上下文到云端。 */
    val cloudAiWifiOnly: Boolean = true,
    val cloudAiProvider: CloudAiProvider = CloudAiProvider.MIMO,
    val cloudAiModel: String = CloudAiProvider.MIMO.defaultModel,
    val mimoCloudAiModel: String = CloudAiProvider.MIMO.defaultModel,
    val mimoCloudAiBaseUrl: String = "",
    val geminiCloudAiModel: String = CloudAiProvider.GEMINI.defaultModel,
    val anthropicCloudAiModel: String = CloudAiProvider.ANTHROPIC.defaultModel,
    val anthropicCloudAiBaseUrl: String = "",
    val openAiCompatibleCloudAiModel: String = CloudAiProvider.OPENAI_COMPATIBLE.defaultModel,
    val openAiCompatibleBaseUrl: String = "",
    val openAiCompatibleAuthMode: OpenAiCompatibleCloudAuthMode = OpenAiCompatibleCloudAuthMode.BEARER,
    val openAiCompatibleProviderName: String = "OpenAI-compatible",
) {
    fun cloudAiModelFor(provider: CloudAiProvider): String = when (provider) {
        CloudAiProvider.MIMO -> mimoCloudAiModel.ifBlank { provider.defaultModel }
        CloudAiProvider.GEMINI -> geminiCloudAiModel.ifBlank { provider.defaultModel }
        CloudAiProvider.ANTHROPIC -> anthropicCloudAiModel.ifBlank { provider.defaultModel }
        CloudAiProvider.OPENAI_COMPATIBLE -> openAiCompatibleCloudAiModel.ifBlank { provider.defaultModel }
    }

    fun activeCloudAiModel(): String {
        val providerModel = cloudAiModelFor(cloudAiProvider)
        val providerHasExplicitModel = providerModel != cloudAiProvider.defaultModel
        val looksLikeLegacyMimoDefault =
            cloudAiProvider != CloudAiProvider.MIMO && cloudAiModel == CloudAiProvider.MIMO.defaultModel
        val selectedModel = when {
            cloudAiModel.isBlank() -> providerModel
            looksLikeLegacyMimoDefault -> providerModel
            providerHasExplicitModel -> providerModel
            else -> cloudAiModel
        }
        return selectedModel.ifBlank { cloudAiProvider.defaultModel }
    }
}

internal fun SettingsPreferences.routineSchedule(): RoutineSchedule = RoutineSchedule(
    wake = RoutineTime(wakeHour, wakeMinute),
    breakfast = RoutineTime(breakfastHour, breakfastMinute),
    lunch = RoutineTime(lunchHour, lunchMinute),
    dinner = RoutineTime(dinnerHour, dinnerMinute),
    bed = RoutineTime(bedHour, bedMinute),
)

fun SettingsPreferences.reminderZone(fallback: ZoneId): ZoneId = if (travelMode && homeTimeZoneId.isNotBlank()) {
    runCatching { ZoneId.of(homeTimeZoneId) }.getOrDefault(fallback)
} else {
    fallback
}

@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val activeRecipient: ActiveRecipientStore,
) : AppearancePreferences,
    ReminderPreferences,
    FeaturePreferences,
    AiPreferences,
    WidgetPreferences,
    OnboardingPreferences {
    private val dataStore: DataStore<Preferences> = context.settingsDataStore

    companion object Keys {
        val PERSISTENT_REMINDER = booleanPreferencesKey("persistent_reminder")
        val PERSISTENT_INTERVAL_MINUTES = intPreferencesKey("persistent_interval_minutes")
        val WAKE_HOUR = intPreferencesKey("wake_hour")
        val WAKE_MINUTE = intPreferencesKey("wake_minute")
        val BREAKFAST_HOUR = intPreferencesKey("breakfast_hour")
        val BREAKFAST_MIN = intPreferencesKey("breakfast_minute")
        val LUNCH_HOUR = intPreferencesKey("lunch_hour")
        val LUNCH_MIN = intPreferencesKey("lunch_minute")
        val DINNER_HOUR = intPreferencesKey("dinner_hour")
        val DINNER_MIN = intPreferencesKey("dinner_minute")
        val BED_HOUR = intPreferencesKey("bed_hour")
        val BED_MIN = intPreferencesKey("bed_minute")
        val HAS_SEEN_WELCOME = booleanPreferencesKey("has_seen_welcome")
        val TRAVEL_MODE = booleanPreferencesKey("travel_mode")
        val HOME_TIMEZONE_ID = stringPreferencesKey("home_timezone_id")

        // 可选功能开关
        val ENABLE_SYMPTOM_DIARY = booleanPreferencesKey("enable_symptom_diary")
        val ENABLE_DRUG_INTERACTION = booleanPreferencesKey("enable_drug_interaction")
        val ENABLE_DRUG_DATABASE = booleanPreferencesKey("enable_drug_database")
        val ENABLE_HEALTH_MODULE = booleanPreferencesKey("enable_health_module")
        val ENABLE_TIME_PERIOD_MODE = booleanPreferencesKey("enable_time_period_mode")

        // 外观
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
        val FONT_MODE = stringPreferencesKey("font_mode")
        val APP_TEXT_SCALE = stringPreferencesKey("app_text_scale")
        val UI_DENSITY_SCALE = stringPreferencesKey("ui_density_scale")

        // 今日页面显示偏好
        val AUTO_COLLAPSE_DONE = booleanPreferencesKey("auto_collapse_completed_groups")
        val HOME_HERO_STYLE = stringPreferencesKey("home_hero_style")
        val MEDICATION_SORT_ORDER = stringPreferencesKey("medication_sort_order")

        // 提前预告提醒
        val EARLY_REMINDER_MINUTES = intPreferencesKey("early_reminder_minutes")

        // 小组件显示偏好
        val WIDGET_SHOW_ACTIONS = booleanPreferencesKey("widget_show_actions")
        val WIDGET_THEME_MODE = stringPreferencesKey("widget_theme_mode")
        val WIDGET_COLOR_SOURCE = stringPreferencesKey("widget_color_source")
        val WIDGET_PALETTE = stringPreferencesKey("widget_palette")
        val WIDGET_DENSITY_SCALE = stringPreferencesKey("widget_density_scale")
        val WIDGET_TEXT_SCALE = stringPreferencesKey("widget_text_scale")

        // 漏服再提醒
        val FOLLOW_UP_ENABLED = booleanPreferencesKey("follow_up_reminder_enabled")
        val FOLLOW_UP_DELAY_MINUTES = intPreferencesKey("follow_up_delay_minutes")
        val FOLLOW_UP_MAX_COUNT = intPreferencesKey("follow_up_max_count")

        // 健康模块
        val USER_HEIGHT_CM = floatPreferencesKey("user_height_cm")

        // OCR 识别设置
        val OCR_MODEL_TYPE = stringPreferencesKey("ocr_model_type")

        // 云端 AI 设置
        val CLOUD_AI_ENABLED = booleanPreferencesKey("cloud_ai_enabled")
        val CLOUD_AI_IMAGE_ANALYSIS_ENABLED = booleanPreferencesKey("cloud_ai_image_analysis_enabled")
        val CLOUD_AI_HEALTH_INSIGHTS_ENABLED = booleanPreferencesKey("cloud_ai_health_insights_enabled")
        val CLOUD_AI_WIFI_ONLY = booleanPreferencesKey("cloud_ai_wifi_only")
        val CLOUD_AI_PROVIDER = stringPreferencesKey("cloud_ai_provider")
        val CLOUD_AI_MODEL = stringPreferencesKey("cloud_ai_model")
        val CLOUD_AI_MIMO_MODEL = stringPreferencesKey("cloud_ai_mimo_model")
        val CLOUD_AI_MIMO_BASE_URL = stringPreferencesKey("cloud_ai_mimo_base_url")
        val CLOUD_AI_GEMINI_MODEL = stringPreferencesKey("cloud_ai_gemini_model")
        val CLOUD_AI_ANTHROPIC_MODEL = stringPreferencesKey("cloud_ai_anthropic_model")
        val CLOUD_AI_ANTHROPIC_BASE_URL = stringPreferencesKey("cloud_ai_anthropic_base_url")
        val CLOUD_AI_OPENAI_COMPATIBLE_MODEL = stringPreferencesKey("cloud_ai_openai_compatible_model")
        val OPENAI_COMPATIBLE_BASE_URL = stringPreferencesKey("openai_compatible_base_url")
        val OPENAI_COMPATIBLE_AUTH_MODE = stringPreferencesKey("openai_compatible_auth_mode")
        val OPENAI_COMPATIBLE_PROVIDER_NAME = stringPreferencesKey("openai_compatible_provider_name")

        private fun cloudAiModelKey(provider: CloudAiProvider): Preferences.Key<String> = when (provider) {
            CloudAiProvider.MIMO -> CLOUD_AI_MIMO_MODEL
            CloudAiProvider.GEMINI -> CLOUD_AI_GEMINI_MODEL
            CloudAiProvider.ANTHROPIC -> CLOUD_AI_ANTHROPIC_MODEL
            CloudAiProvider.OPENAI_COMPATIBLE -> CLOUD_AI_OPENAI_COMPATIBLE_MODEL
        }
    }

    /** 设备级设置（不含按成员拆分的作息/时区/身高）；对外请使用 [settingsFlow]。 */
    private val deviceSettingsFlow: Flow<SettingsPreferences> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            val cloudAiProvider = prefs[CLOUD_AI_PROVIDER]?.let {
                runCatching { CloudAiProvider.valueOf(it) }.getOrNull()
            } ?: CloudAiProvider.MIMO
            val defaultRoutine = RoutineSchedule()
            val wakeTime = RoutineTime.fromStoredOrDefault(
                prefs[WAKE_HOUR],
                prefs[WAKE_MINUTE],
                defaultRoutine.wake,
            )
            val breakfastTime = RoutineTime.fromStoredOrDefault(
                prefs[BREAKFAST_HOUR],
                prefs[BREAKFAST_MIN],
                defaultRoutine.breakfast,
            )
            val lunchTime = RoutineTime.fromStoredOrDefault(
                prefs[LUNCH_HOUR],
                prefs[LUNCH_MIN],
                defaultRoutine.lunch,
            )
            val dinnerTime = RoutineTime.fromStoredOrDefault(
                prefs[DINNER_HOUR],
                prefs[DINNER_MIN],
                defaultRoutine.dinner,
            )
            val bedTime = RoutineTime.fromStoredOrDefault(
                prefs[BED_HOUR],
                prefs[BED_MIN],
                defaultRoutine.bed,
            )
            val legacyCloudAiModel = prefs[CLOUD_AI_MODEL]
            fun storedModel(provider: CloudAiProvider): String {
                val legacyForSelectedProvider = if (cloudAiProvider == provider) legacyCloudAiModel else null
                return prefs[cloudAiModelKey(provider)] ?: legacyForSelectedProvider ?: provider.defaultModel
            }
            val mimoCloudAiModel = storedModel(CloudAiProvider.MIMO)
            val geminiCloudAiModel = storedModel(CloudAiProvider.GEMINI)
            val anthropicCloudAiModel = storedModel(CloudAiProvider.ANTHROPIC)
            val openAiCompatibleCloudAiModel = storedModel(CloudAiProvider.OPENAI_COMPATIBLE)

            SettingsPreferences(
                persistentReminder = prefs[PERSISTENT_REMINDER] ?: false,
                persistentIntervalMinutes = prefs[PERSISTENT_INTERVAL_MINUTES] ?: 5,
                wakeHour = wakeTime.hour, wakeMinute = wakeTime.minute,
                breakfastHour = breakfastTime.hour, breakfastMinute = breakfastTime.minute,
                lunchHour = lunchTime.hour, lunchMinute = lunchTime.minute,
                dinnerHour = dinnerTime.hour, dinnerMinute = dinnerTime.minute,
                bedHour = bedTime.hour, bedMinute = bedTime.minute,
                hasSeenWelcome = prefs[HAS_SEEN_WELCOME] ?: false,
                travelMode = prefs[TRAVEL_MODE] ?: false,
                homeTimeZoneId = prefs[HOME_TIMEZONE_ID] ?: "",
                enableSymptomDiary = prefs[ENABLE_SYMPTOM_DIARY] ?: true,
                enableDrugInteractionCheck = prefs[ENABLE_DRUG_INTERACTION] ?: true,
                enableDrugDatabase = prefs[ENABLE_DRUG_DATABASE] ?: true,
                enableHealthModule = prefs[ENABLE_HEALTH_MODULE] ?: true,
                enableTimePeriodMode = prefs[ENABLE_TIME_PERIOD_MODE] ?: true,
                themeMode = prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                useDynamicColor = prefs[USE_DYNAMIC_COLOR] ?: false,
                themePaletteName = prefs[THEME_PALETTE] ?: "ANSHIN",
                fontMode = FontMode.fromStoredName(prefs[FONT_MODE]),
                appTextScale = AppTextScale.fromStoredName(prefs[APP_TEXT_SCALE]),
                uiDensityScale = UiDensityScale.fromStoredName(prefs[UI_DENSITY_SCALE]),
                autoCollapseCompletedGroups = prefs[AUTO_COLLAPSE_DONE] ?: true,
                homeHeroStyle = HomeHeroStyle.fromStoredName(prefs[HOME_HERO_STYLE]),
                medicationSortOrder = MedicationSortOrder.fromStoredName(prefs[MEDICATION_SORT_ORDER]),
                earlyReminderMinutes = prefs[EARLY_REMINDER_MINUTES] ?: 0,
                widgetShowActions = prefs[WIDGET_SHOW_ACTIONS] ?: true,
                widgetThemeMode = WidgetThemeMode.fromStoredName(prefs[WIDGET_THEME_MODE]),
                widgetColorSource = WidgetColorSource.fromStoredName(prefs[WIDGET_COLOR_SOURCE]),
                widgetPaletteName = prefs[WIDGET_PALETTE] ?: "ANSHIN",
                widgetDensityScale = WidgetDensityScale.fromStoredName(prefs[WIDGET_DENSITY_SCALE]),
                widgetTextScale = WidgetTextScale.fromStoredName(prefs[WIDGET_TEXT_SCALE]),
                followUpReminderEnabled = prefs[FOLLOW_UP_ENABLED] ?: false,
                followUpDelayMinutes = prefs[FOLLOW_UP_DELAY_MINUTES] ?: 15,
                followUpMaxCount = prefs[FOLLOW_UP_MAX_COUNT] ?: 1,
                userHeightCm = prefs[USER_HEIGHT_CM] ?: 0f,
                ocrModelType =
                prefs[OCR_MODEL_TYPE]?.let { runCatching { OcrModelType.valueOf(it) }.getOrNull() }
                    ?: OcrModelType.LIGHT_SVTR,
                cloudAiEnabled = prefs[CLOUD_AI_ENABLED] ?: false,
                cloudAiImageAnalysisEnabled = prefs[CLOUD_AI_IMAGE_ANALYSIS_ENABLED] ?: false,
                cloudAiHealthInsightsEnabled = prefs[CLOUD_AI_HEALTH_INSIGHTS_ENABLED] ?: false,
                cloudAiWifiOnly = prefs[CLOUD_AI_WIFI_ONLY] ?: true,
                cloudAiProvider = cloudAiProvider,
                cloudAiModel = when (cloudAiProvider) {
                    CloudAiProvider.MIMO -> mimoCloudAiModel
                    CloudAiProvider.GEMINI -> geminiCloudAiModel
                    CloudAiProvider.ANTHROPIC -> anthropicCloudAiModel
                    CloudAiProvider.OPENAI_COMPATIBLE -> openAiCompatibleCloudAiModel
                },
                mimoCloudAiModel = mimoCloudAiModel,
                mimoCloudAiBaseUrl = prefs[CLOUD_AI_MIMO_BASE_URL] ?: "",
                geminiCloudAiModel = geminiCloudAiModel,
                anthropicCloudAiModel = anthropicCloudAiModel,
                anthropicCloudAiBaseUrl = prefs[CLOUD_AI_ANTHROPIC_BASE_URL] ?: "",
                openAiCompatibleCloudAiModel = openAiCompatibleCloudAiModel,
                openAiCompatibleBaseUrl = prefs[OPENAI_COMPATIBLE_BASE_URL] ?: "",
                openAiCompatibleAuthMode = prefs[OPENAI_COMPATIBLE_AUTH_MODE]?.let {
                    runCatching { OpenAiCompatibleCloudAuthMode.valueOf(it) }.getOrNull()
                } ?: OpenAiCompatibleCloudAuthMode.BEARER,
                openAiCompatibleProviderName = prefs[OPENAI_COMPATIBLE_PROVIDER_NAME] ?: "OpenAI-compatible",
            )
        }

    // ── 按成员(per-recipient)的作息 / 时区 / 身高 ─────────────────────────────
    //
    // 落点决策（阶段 1）：仍存 DataStore，但按成员分键（`<legacy>#<recipientId>`）；
    // 读取顺序是「成员键 → 改造前的全局键 → 默认值」，写入时若还没有成员则沿用全局键。
    // 因此升级用户不需要任何数据迁移，也不必动 Room schema（v19 不变）。

    /** 成员维度键；没有成员时返回旧全局键，首启/引导期的行为与改造前一致。 */
    private fun intKey(legacy: Preferences.Key<Int>, recipientId: Long): Preferences.Key<Int> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            legacy
        } else {
            intPreferencesKey("${legacy.name}#$recipientId")
        }

    private fun booleanKey(legacy: Preferences.Key<Boolean>, recipientId: Long): Preferences.Key<Boolean> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            legacy
        } else {
            booleanPreferencesKey("${legacy.name}#$recipientId")
        }

    private fun stringKey(legacy: Preferences.Key<String>, recipientId: Long): Preferences.Key<String> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            legacy
        } else {
            stringPreferencesKey("${legacy.name}#$recipientId")
        }

    private fun floatKey(legacy: Preferences.Key<Float>, recipientId: Long): Preferences.Key<Float> =
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            legacy
        } else {
            floatPreferencesKey("${legacy.name}#$recipientId")
        }

    /** 读取顺序：成员键 → 旧全局键（升级用户的既有设置）。 */
    private fun Preferences.memberInt(legacy: Preferences.Key<Int>, recipientId: Long): Int? =
        this[intKey(legacy, recipientId)] ?: this[legacy]

    private fun Preferences.memberBoolean(legacy: Preferences.Key<Boolean>, recipientId: Long): Boolean? =
        this[booleanKey(legacy, recipientId)] ?: this[legacy]

    private fun Preferences.memberString(legacy: Preferences.Key<String>, recipientId: Long): String? =
        this[stringKey(legacy, recipientId)] ?: this[legacy]

    private fun Preferences.memberFloat(legacy: Preferences.Key<Float>, recipientId: Long): Float? =
        this[floatKey(legacy, recipientId)] ?: this[legacy]

    /**
     * 当前成员视角的设置：只覆盖作息、时区、身高三类字段，其余字段仍是设备级。
     * 覆盖放在数据类层面，既有的整段映射逻辑保持不动。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val settingsFlow: Flow<SettingsPreferences> = activeRecipient.recipientId
        .flatMapLatest { recipientId ->
            deviceSettingsFlow.map { device -> device.withMemberScopedPrefs(recipientId) }
        }
        .distinctUntilChanged()

    private suspend fun SettingsPreferences.withMemberScopedPrefs(recipientId: Long): SettingsPreferences {
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return this
        val prefs = dataStore.data.first()
        val wake = RoutineTime(
            prefs.memberInt(WAKE_HOUR, recipientId) ?: wakeHour,
            prefs.memberInt(WAKE_MINUTE, recipientId) ?: wakeMinute,
        )
        val breakfast = RoutineTime(
            prefs.memberInt(BREAKFAST_HOUR, recipientId) ?: breakfastHour,
            prefs.memberInt(BREAKFAST_MIN, recipientId) ?: breakfastMinute,
        )
        val lunch = RoutineTime(
            prefs.memberInt(LUNCH_HOUR, recipientId) ?: lunchHour,
            prefs.memberInt(LUNCH_MIN, recipientId) ?: lunchMinute,
        )
        val dinner = RoutineTime(
            prefs.memberInt(DINNER_HOUR, recipientId) ?: dinnerHour,
            prefs.memberInt(DINNER_MIN, recipientId) ?: dinnerMinute,
        )
        val bed = RoutineTime(
            prefs.memberInt(BED_HOUR, recipientId) ?: bedHour,
            prefs.memberInt(BED_MIN, recipientId) ?: bedMinute,
        )
        return copy(
            wakeHour = wake.hour,
            wakeMinute = wake.minute,
            breakfastHour = breakfast.hour,
            breakfastMinute = breakfast.minute,
            lunchHour = lunch.hour,
            lunchMinute = lunch.minute,
            dinnerHour = dinner.hour,
            dinnerMinute = dinner.minute,
            bedHour = bed.hour,
            bedMinute = bed.minute,
            travelMode = prefs.memberBoolean(TRAVEL_MODE, recipientId) ?: travelMode,
            homeTimeZoneId = prefs.memberString(HOME_TIMEZONE_ID, recipientId) ?: homeTimeZoneId,
            userHeightCm = prefs.memberFloat(USER_HEIGHT_CM, recipientId) ?: userHeightCm,
        )
    }

    /** 指定成员的作息；供提醒重排等"非当前成员"场景使用（成员键缺失时回落旧全局键）。 */
    suspend fun routineScheduleFor(recipientId: Long): RoutineSchedule {
        val prefs = dataStore.data.first()
        val base = RoutineSchedule()
        fun time(hourKey: Preferences.Key<Int>, minuteKey: Preferences.Key<Int>, fallback: RoutineTime) = RoutineTime(
            prefs.memberInt(hourKey, recipientId) ?: fallback.hour,
            prefs.memberInt(minuteKey, recipientId) ?: fallback.minute,
        )
        return RoutineSchedule(
            wake = time(WAKE_HOUR, WAKE_MINUTE, base.wake),
            breakfast = time(BREAKFAST_HOUR, BREAKFAST_MIN, base.breakfast),
            lunch = time(LUNCH_HOUR, LUNCH_MIN, base.lunch),
            dinner = time(DINNER_HOUR, DINNER_MIN, base.dinner),
            bed = time(BED_HOUR, BED_MIN, base.bed),
        )
    }

    /** 指定成员的提醒时区（旅行模式 + 家乡时区按成员解析）。 */
    suspend fun reminderZoneFor(recipientId: Long, fallback: ZoneId): ZoneId {
        val prefs = dataStore.data.first()
        val travelMode = prefs.memberBoolean(TRAVEL_MODE, recipientId) ?: false
        val homeTimeZoneId = prefs.memberString(HOME_TIMEZONE_ID, recipientId).orEmpty()
        return if (travelMode && homeTimeZoneId.isNotBlank()) {
            runCatching { ZoneId.of(homeTimeZoneId) }.getOrDefault(fallback)
        } else {
            fallback
        }
    }

    /** 删除成员时清理其成员维度的设置键。 */
    suspend fun clearMemberScopedSettings(recipientId: Long) {
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return
        dataStore.edit { prefs ->
            listOf(WAKE_HOUR, WAKE_MINUTE, BREAKFAST_HOUR, BREAKFAST_MIN, LUNCH_HOUR, LUNCH_MIN)
                .forEach { prefs.remove(intKey(it, recipientId)) }
            listOf(DINNER_HOUR, DINNER_MIN, BED_HOUR, BED_MIN)
                .forEach { prefs.remove(intKey(it, recipientId)) }
            prefs.remove(booleanKey(TRAVEL_MODE, recipientId))
            prefs.remove(stringKey(HOME_TIMEZONE_ID, recipientId))
            prefs.remove(floatKey(USER_HEIGHT_CM, recipientId))
        }
    }

    override val appearance: Flow<AppearancePreferenceState> = settingsFlow.map { prefs ->
        AppearancePreferenceState(
            themeMode = prefs.themeMode,
            useDynamicColor = prefs.useDynamicColor,
            themePaletteName = prefs.themePaletteName,
            fontMode = prefs.fontMode,
            appTextScale = prefs.appTextScale,
            uiDensityScale = prefs.uiDensityScale,
            autoCollapseCompletedGroups = prefs.autoCollapseCompletedGroups,
            homeHeroStyle = prefs.homeHeroStyle,
            medicationSortOrder = prefs.medicationSortOrder,
        )
    }.distinctUntilChanged()

    override val reminders: Flow<ReminderPreferenceState> = settingsFlow.map { prefs ->
        ReminderPreferenceState(
            persistentReminder = prefs.persistentReminder,
            persistentIntervalMinutes = prefs.persistentIntervalMinutes,
            routineSchedule = prefs.routineSchedule(),
            travelMode = prefs.travelMode,
            homeTimeZoneId = prefs.homeTimeZoneId,
            earlyReminderMinutes = prefs.earlyReminderMinutes,
            followUpReminderEnabled = prefs.followUpReminderEnabled,
            followUpDelayMinutes = prefs.followUpDelayMinutes,
            followUpMaxCount = prefs.followUpMaxCount,
        )
    }.distinctUntilChanged()

    override val features: Flow<FeaturePreferenceState> = settingsFlow.map { prefs ->
        FeaturePreferenceState(
            enableSymptomDiary = prefs.enableSymptomDiary,
            enableDrugInteractionCheck = prefs.enableDrugInteractionCheck,
            enableDrugDatabase = prefs.enableDrugDatabase,
            enableHealthModule = prefs.enableHealthModule,
            enableTimePeriodMode = prefs.enableTimePeriodMode,
        )
    }.distinctUntilChanged()

    override val ai: Flow<AiPreferenceState> = settingsFlow.map { prefs ->
        AiPreferenceState(
            ocrModelType = prefs.ocrModelType,
            cloudAiEnabled = prefs.cloudAiEnabled,
            cloudAiImageAnalysisEnabled = prefs.cloudAiImageAnalysisEnabled,
            cloudAiHealthInsightsEnabled = prefs.cloudAiHealthInsightsEnabled,
            cloudAiWifiOnly = prefs.cloudAiWifiOnly,
            cloudAiProvider = prefs.cloudAiProvider,
            cloudAiModel = prefs.activeCloudAiModel(),
            mimoCloudAiBaseUrl = prefs.mimoCloudAiBaseUrl,
            anthropicCloudAiBaseUrl = prefs.anthropicCloudAiBaseUrl,
            openAiCompatibleBaseUrl = prefs.openAiCompatibleBaseUrl,
            openAiCompatibleAuthMode = prefs.openAiCompatibleAuthMode,
            openAiCompatibleProviderName = prefs.openAiCompatibleProviderName,
        )
    }.distinctUntilChanged()

    override val widgets: Flow<WidgetPreferenceState> = settingsFlow.map { prefs ->
        WidgetPreferenceState(
            showActions = prefs.widgetShowActions,
            themeMode = prefs.widgetThemeMode,
            colorSource = prefs.widgetColorSource,
            paletteName = prefs.widgetPaletteName,
            densityScale = prefs.widgetDensityScale,
            textScale = prefs.widgetTextScale,
        )
    }.distinctUntilChanged()

    override val onboarding: Flow<OnboardingPreferenceState> = settingsFlow
        .map { OnboardingPreferenceState(it.hasSeenWelcome) }
        .distinctUntilChanged()

    override suspend fun updatePersistentReminder(enabled: Boolean) {
        dataStore.edit { it[PERSISTENT_REMINDER] = enabled }
    }

    override suspend fun updatePersistentInterval(minutes: Int) {
        dataStore.edit { it[PERSISTENT_INTERVAL_MINUTES] = minutes }
    }

    override suspend fun updateRoutineTime(slot: RoutineTimeSlot, time: RoutineTime) {
        val recipientId = activeRecipient.current()
        dataStore.edit { prefs ->
            when (slot) {
                RoutineTimeSlot.WAKE -> {
                    prefs[intKey(WAKE_HOUR, recipientId)] = time.hour
                    prefs[intKey(WAKE_MINUTE, recipientId)] = time.minute
                }
                RoutineTimeSlot.BREAKFAST -> {
                    prefs[intKey(BREAKFAST_HOUR, recipientId)] = time.hour
                    prefs[intKey(BREAKFAST_MIN, recipientId)] = time.minute
                }
                RoutineTimeSlot.LUNCH -> {
                    prefs[intKey(LUNCH_HOUR, recipientId)] = time.hour
                    prefs[intKey(LUNCH_MIN, recipientId)] = time.minute
                }
                RoutineTimeSlot.DINNER -> {
                    prefs[intKey(DINNER_HOUR, recipientId)] = time.hour
                    prefs[intKey(DINNER_MIN, recipientId)] = time.minute
                }
                RoutineTimeSlot.BED -> {
                    prefs[intKey(BED_HOUR, recipientId)] = time.hour
                    prefs[intKey(BED_MIN, recipientId)] = time.minute
                }
            }
        }
    }

    override suspend fun updateRoutineSchedule(schedule: RoutineSchedule) {
        val recipientId = activeRecipient.current()
        dataStore.edit { prefs ->
            prefs[intKey(WAKE_HOUR, recipientId)] = schedule.wake.hour
            prefs[intKey(WAKE_MINUTE, recipientId)] = schedule.wake.minute
            prefs[intKey(BREAKFAST_HOUR, recipientId)] = schedule.breakfast.hour
            prefs[intKey(BREAKFAST_MIN, recipientId)] = schedule.breakfast.minute
            prefs[intKey(LUNCH_HOUR, recipientId)] = schedule.lunch.hour
            prefs[intKey(LUNCH_MIN, recipientId)] = schedule.lunch.minute
            prefs[intKey(DINNER_HOUR, recipientId)] = schedule.dinner.hour
            prefs[intKey(DINNER_MIN, recipientId)] = schedule.dinner.minute
            prefs[intKey(BED_HOUR, recipientId)] = schedule.bed.hour
            prefs[intKey(BED_MIN, recipientId)] = schedule.bed.minute
        }
    }

    override suspend fun updateHasSeenWelcome(seen: Boolean) {
        dataStore.edit { it[HAS_SEEN_WELCOME] = seen }
    }

    /** Writes the complete onboarding draft atomically so observers never see a partially applied setup. */
    override suspend fun saveOnboardingDraft(draft: OnboardingDraft) {
        val recipientId = activeRecipient.current()
        dataStore.edit { prefs ->
            prefs[intKey(WAKE_HOUR, recipientId)] = draft.routineSchedule.wake.hour
            prefs[intKey(WAKE_MINUTE, recipientId)] = draft.routineSchedule.wake.minute
            prefs[intKey(BREAKFAST_HOUR, recipientId)] = draft.routineSchedule.breakfast.hour
            prefs[intKey(BREAKFAST_MIN, recipientId)] = draft.routineSchedule.breakfast.minute
            prefs[intKey(LUNCH_HOUR, recipientId)] = draft.routineSchedule.lunch.hour
            prefs[intKey(LUNCH_MIN, recipientId)] = draft.routineSchedule.lunch.minute
            prefs[intKey(DINNER_HOUR, recipientId)] = draft.routineSchedule.dinner.hour
            prefs[intKey(DINNER_MIN, recipientId)] = draft.routineSchedule.dinner.minute
            prefs[intKey(BED_HOUR, recipientId)] = draft.routineSchedule.bed.hour
            prefs[intKey(BED_MIN, recipientId)] = draft.routineSchedule.bed.minute
            prefs[ENABLE_SYMPTOM_DIARY] = draft.enableSymptomDiary
            prefs[ENABLE_DRUG_INTERACTION] = draft.enableDrugInteractionCheck
            prefs[ENABLE_DRUG_DATABASE] = draft.enableDrugDatabase
            prefs[ENABLE_HEALTH_MODULE] = draft.enableHealthModule
            prefs[ENABLE_TIME_PERIOD_MODE] = draft.enableTimePeriodMode
            prefs[THEME_MODE] = draft.themeMode.name
        }
    }

    override suspend fun updateTravelMode(enabled: Boolean, homeTimeZoneId: String) {
        val recipientId = activeRecipient.current()
        dataStore.edit {
            it[booleanKey(TRAVEL_MODE, recipientId)] = enabled
            if (homeTimeZoneId.isNotBlank()) it[stringKey(HOME_TIMEZONE_ID, recipientId)] = homeTimeZoneId
        }
    }

    /**
     * 更新可选功能开关（null = 保持原值不变）。
     */
    override suspend fun updateFeatureFlags(
        enableSymptomDiary: Boolean?,
        enableDrugInteraction: Boolean?,
        enableDrugDatabase: Boolean?,
        enableHealthModule: Boolean?,
        enableTimePeriodMode: Boolean?,
    ) {
        dataStore.edit { prefs ->
            if (enableSymptomDiary != null) prefs[ENABLE_SYMPTOM_DIARY] = enableSymptomDiary
            if (enableDrugInteraction != null) prefs[ENABLE_DRUG_INTERACTION] = enableDrugInteraction
            if (enableDrugDatabase != null) prefs[ENABLE_DRUG_DATABASE] = enableDrugDatabase
            if (enableHealthModule != null) prefs[ENABLE_HEALTH_MODULE] = enableHealthModule
            if (enableTimePeriodMode != null) prefs[ENABLE_TIME_PERIOD_MODE] = enableTimePeriodMode
        }
    }

    /** 更新外观主题模式 */
    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = themeMode.name }
    }

    /** 更新动态颜色（Material You）开关 */
    override suspend fun updateUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[USE_DYNAMIC_COLOR] = enabled }
    }

    /** 更新主题配色方案 */
    override suspend fun updateThemePalette(paletteName: String) {
        dataStore.edit { it[THEME_PALETTE] = paletteName }
    }

    override suspend fun updateFontMode(fontMode: FontMode) {
        dataStore.edit { it[FONT_MODE] = fontMode.name }
    }

    override suspend fun updateAppTextScale(scale: AppTextScale) {
        dataStore.edit { it[APP_TEXT_SCALE] = scale.name }
    }

    override suspend fun updateUiDensityScale(scale: UiDensityScale) {
        dataStore.edit { it[UI_DENSITY_SCALE] = scale.name }
    }

    /** 更新「已完成分组默认折叠」开关 */
    override suspend fun updateAutoCollapseCompletedGroups(enabled: Boolean) {
        dataStore.edit { it[AUTO_COLLAPSE_DONE] = enabled }
    }

    override suspend fun updateHomeHeroStyle(style: HomeHeroStyle) {
        dataStore.edit { it[HOME_HERO_STYLE] = style.name }
    }

    override suspend fun updateMedicationSortOrder(order: MedicationSortOrder) {
        dataStore.edit { it[MEDICATION_SORT_ORDER] = order.name }
    }

    /** 更新提前预告提醒分钟数（0 = 关闭） */
    override suspend fun updateEarlyReminderMinutes(minutes: Int) {
        dataStore.edit { it[EARLY_REMINDER_MINUTES] = minutes }
    }

    /** 更新小组件显示模式（true = 操作按物，false = 状态显示） */
    override suspend fun updateWidgetShowActions(enabled: Boolean) {
        dataStore.edit { it[WIDGET_SHOW_ACTIONS] = enabled }
    }

    override suspend fun updateWidgetAppearance(
        themeMode: WidgetThemeMode?,
        colorSource: WidgetColorSource?,
        paletteName: String?,
        densityScale: WidgetDensityScale?,
        textScale: WidgetTextScale?,
    ) {
        dataStore.edit { prefs ->
            if (themeMode != null) prefs[WIDGET_THEME_MODE] = themeMode.name
            if (colorSource != null) prefs[WIDGET_COLOR_SOURCE] = colorSource.name
            if (paletteName != null) prefs[WIDGET_PALETTE] = paletteName
            if (densityScale != null) prefs[WIDGET_DENSITY_SCALE] = densityScale.name
            if (textScale != null) prefs[WIDGET_TEXT_SCALE] = textScale.name
        }
    }

    /** 更新漏服再提醒设置 */
    override suspend fun updateFollowUpSettings(enabled: Boolean?, delayMinutes: Int?, maxCount: Int?) {
        dataStore.edit { prefs ->
            if (enabled != null) prefs[FOLLOW_UP_ENABLED] = enabled
            if (delayMinutes != null) prefs[FOLLOW_UP_DELAY_MINUTES] = delayMinutes
            if (maxCount != null) prefs[FOLLOW_UP_MAX_COUNT] = maxCount
        }
    }

    /** 更新用户身高（cm），用于 BMI 计算 */
    suspend fun updateUserHeight(heightCm: Float) {
        val recipientId = activeRecipient.current()
        dataStore.edit { it[floatKey(USER_HEIGHT_CM, recipientId)] = heightCm }
    }

    /** 更新 OCR 识别模型类型 */
    override suspend fun updateOcrModelType(modelType: OcrModelType) {
        dataStore.edit { it[OCR_MODEL_TYPE] = modelType.name }
    }

    override suspend fun updateCloudAiSettings(
        enabled: Boolean?,
        imageAnalysisEnabled: Boolean?,
        healthInsightsEnabled: Boolean?,
        wifiOnly: Boolean?,
        provider: CloudAiProvider?,
        model: String?,
        mimoBaseUrl: String?,
        anthropicBaseUrl: String?,
        openAiCompatibleBaseUrl: String?,
        openAiCompatibleAuthMode: OpenAiCompatibleCloudAuthMode?,
        openAiCompatibleProviderName: String?,
    ) {
        dataStore.edit { prefs ->
            val selectedProvider = provider ?: prefs[CLOUD_AI_PROVIDER]?.let {
                runCatching { CloudAiProvider.valueOf(it) }.getOrNull()
            } ?: CloudAiProvider.MIMO
            if (enabled != null) prefs[CLOUD_AI_ENABLED] = enabled
            if (imageAnalysisEnabled != null) prefs[CLOUD_AI_IMAGE_ANALYSIS_ENABLED] = imageAnalysisEnabled
            if (healthInsightsEnabled != null) prefs[CLOUD_AI_HEALTH_INSIGHTS_ENABLED] = healthInsightsEnabled
            if (wifiOnly != null) prefs[CLOUD_AI_WIFI_ONLY] = wifiOnly
            if (provider != null) {
                prefs[CLOUD_AI_PROVIDER] = provider.name
                if (model == null) {
                    prefs[CLOUD_AI_MODEL] = prefs[cloudAiModelKey(provider)] ?: provider.defaultModel
                }
            }
            if (model != null) {
                val resolvedModel = model.ifBlank { selectedProvider.defaultModel }
                prefs[CLOUD_AI_MODEL] = resolvedModel
                prefs[cloudAiModelKey(selectedProvider)] = resolvedModel
            }
            if (mimoBaseUrl != null) prefs[CLOUD_AI_MIMO_BASE_URL] = mimoBaseUrl.trim()
            if (anthropicBaseUrl != null) prefs[CLOUD_AI_ANTHROPIC_BASE_URL] = anthropicBaseUrl.trim()
            if (openAiCompatibleBaseUrl != null) prefs[OPENAI_COMPATIBLE_BASE_URL] = openAiCompatibleBaseUrl.trim()
            if (openAiCompatibleAuthMode != null) prefs[OPENAI_COMPATIBLE_AUTH_MODE] = openAiCompatibleAuthMode.name
            if (openAiCompatibleProviderName != null) {
                prefs[OPENAI_COMPATIBLE_PROVIDER_NAME] =
                    openAiCompatibleProviderName.ifBlank { "OpenAI-compatible" }
            }
        }
    }
}
