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

    @Serializable data object Diary : Route

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

    /** @param noteId 编辑模式已有记录的 id（-1 代表新增） */
    @Serializable data class CareNoteEditor(val noteId: Long = -1) : Route

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
        Route.Diary,
        MedLogIcons.EditNote,
        MedLogIcons.EditNoteSelected,
        com.driezy.medlog.R.string.tab_diary,
    ),
    TopLevelDestination(
        Route.Health,
        MedLogIcons.MonitorHeart,
        MedLogIcons.MonitorHeartSelected,
        com.driezy.medlog.R.string.tab_health,
    ),
)
