package com.driezy.medlog.ui.navigation

import com.driezy.medlog.ui.icons.MedLogIcons
import kotlinx.serialization.Serializable

// ── Sealed screen routes (type-safe Navigation) ──────────────────────────────

@Serializable sealed interface Route {
    @Serializable data object Welcome : Route

    // 首次启动引导页
    @Serializable data object Home : Route

    @Serializable data object History : Route

    @Serializable data object MyMedications : Route

    @Serializable data object Drugs : Route

    /** 记录中心：统一承载身心记录（SymptomLog）与照护笔记（CareNote）的浏览。 */
    @Serializable data object Records : Route

    @Serializable data object Health : Route

    @Serializable data object Settings : Route

    @Serializable data object SettingsAppearance : Route

    @Serializable data object SettingsReminders : Route

    @Serializable data object SettingsModules : Route

    @Serializable data object SettingsIntelligence : Route

    @Serializable data object SettingsCloudApi : Route

    @Serializable data object SettingsBpx1 : Route

    @Serializable data object SettingsWidgets : Route

    @Serializable data object SettingsData : Route

    @Serializable data object SettingsRecipients : Route

    /** 照护事项（非药物干预）列表。 */
    @Serializable data object CareTasks : Route

    /** 待办列表（进行中 / 历史）。 */
    @Serializable data object Todos : Route

    /** 照护笔记列表（搜索 + 状态过滤）。入口在「更多」溢出菜单（与待办/照护事项并列）。 */
    @Serializable data object CareNotes : Route

    /**
     * @param noteId 编辑模式已有记录的 id（-1 代表新增）
     * @param prelinkType 上下文快捷新增预挂关联的目标类型（空串代表无预挂）
     * @param prelinkId 预挂关联的目标 id（-1 代表无预挂）
     */
    @Serializable
    data class CareNoteEditor(val noteId: Long = -1, val prelinkType: String = "", val prelinkId: Long = -1) : Route

    /** @param todoId 编辑模式已有记录的 id（-1 代表新增） */
    @Serializable data class TodoEditor(val todoId: Long = -1) : Route

    @Serializable data class MedDetail(val medicationId: Long) : Route

    /**
     * @param medicationId  编辑模式下已有记录的 id（-1 代表新增）
     * @param drugName      从药品数据库选中后预填的药品名
     * @param drugCategory  从药品数据库选中后预填的分类
     */
    @Serializable
    data class AddMedication(val medicationId: Long = -1, val drugName: String = "", val drugCategory: String = "") :
        Route

    @Serializable data class CareTaskDetail(val careTaskId: Long) : Route

    /** @param careTaskId 编辑模式已有记录的 id（-1 代表新增） */
    @Serializable data class CareTaskEditor(val careTaskId: Long = -1) : Route
}

// ── Top-level navigation destinations ────────────────────────────────────────

data class TopLevelDestination(val route: Route, val icon: Int, val selectedIcon: Int, val labelRes: Int)

val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(Route.Home, MedLogIcons.Home, MedLogIcons.HomeSelected, com.driezy.medlog.R.string.tab_today),
    TopLevelDestination(
        Route.History,
        MedLogIcons.History,
        MedLogIcons.HistorySelected,
        com.driezy.medlog.R.string.tab_history,
    ),
    TopLevelDestination(
        Route.MyMedications,
        MedLogIcons.MedicalServices,
        MedLogIcons.MedicalServicesSelected,
        com.driezy.medlog.R.string.my_medications,
    ),
    TopLevelDestination(
        Route.Records,
        MedLogIcons.EditNote,
        MedLogIcons.EditNoteSelected,
        com.driezy.medlog.R.string.tab_records,
    ),
    TopLevelDestination(
        Route.Health,
        MedLogIcons.MonitorHeart,
        MedLogIcons.MonitorHeartSelected,
        com.driezy.medlog.R.string.tab_health,
    ),
)

/**
 * 按功能开关过滤可见的顶层目的地（`MedLogApp` 使用）。
 *
 * `enableSymptomDiary` 关闭时隐藏「记录」Tab——语义与既有实现保持一致（`Route.Diary` 改名后
 * 由 `Route.Records` 承接）。注意：隐藏的只是**底部 Tab 入口**；`Route.Records` 仍注册在 NavHost 中，
 * 照护笔记继续经首页「更多」菜单进入记录中心（diary 模式隐藏），因此开关关闭时照护笔记仍可达。
 */
fun visibleTopLevelDestinations(
    enableSymptomDiary: Boolean,
    enableDrugDatabase: Boolean,
    enableHealthModule: Boolean,
): List<TopLevelDestination> = TOP_LEVEL_DESTINATIONS.filter { dest ->
    when (dest.route) {
        Route.Records -> enableSymptomDiary
        Route.Drugs -> enableDrugDatabase
        Route.Health -> enableHealthModule
        else -> true // Home / History / MyMedications 始终可见
    }
}
