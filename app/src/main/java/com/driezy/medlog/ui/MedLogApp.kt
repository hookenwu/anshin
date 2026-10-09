package com.driezy.medlog.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.feature.carenotes.CareNoteEditorScreen
import com.driezy.medlog.feature.carenotes.CareNotesScreen
import com.driezy.medlog.feature.carepeople.CarePeopleScreen
import com.driezy.medlog.feature.caretasks.CareTaskDetailScreen
import com.driezy.medlog.feature.caretasks.CareTaskEditorScreen
import com.driezy.medlog.feature.caretasks.CareTasksScreen
import com.driezy.medlog.feature.health.HealthScreen
import com.driezy.medlog.feature.history.HistoryScreen
import com.driezy.medlog.feature.medications.catalog.DrugsScreen
import com.driezy.medlog.feature.medications.detail.MedicationDetailScreen
import com.driezy.medlog.feature.medications.editor.AddMedicationScreen
import com.driezy.medlog.feature.medications.home.HomeScreen
import com.driezy.medlog.feature.onboarding.WelcomeScreen
import com.driezy.medlog.feature.recipients.CareRecipientGateScreen
import com.driezy.medlog.feature.recipients.CareRecipientsScreen
import com.driezy.medlog.feature.recipients.CareRecipientsUiAction
import com.driezy.medlog.feature.recipients.CareRecipientsViewModel
import com.driezy.medlog.feature.records.RecordsScreen
import com.driezy.medlog.feature.settings.AppearanceSettingsScreen
import com.driezy.medlog.feature.settings.Bpx1DeviceSettingsScreen
import com.driezy.medlog.feature.settings.CloudApiSettingsScreen
import com.driezy.medlog.feature.settings.DataSettingsScreen
import com.driezy.medlog.feature.settings.IntelligenceSettingsScreen
import com.driezy.medlog.feature.settings.ModuleSettingsScreen
import com.driezy.medlog.feature.settings.ReminderSettingsScreen
import com.driezy.medlog.feature.settings.SettingsScreen
import com.driezy.medlog.feature.settings.WidgetSettingsScreen
import com.driezy.medlog.feature.todos.CareTodoEditorScreen
import com.driezy.medlog.feature.todos.CareTodosScreen
import com.driezy.medlog.ui.navigation.MedLogNavigationWrapper
import com.driezy.medlog.ui.navigation.Route
import com.driezy.medlog.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.driezy.medlog.ui.navigation.TopLevelDestination
import com.driezy.medlog.ui.navigation.visibleTopLevelDestinations

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MedLogApp(openAddMedication: Boolean = false) {
    val appViewModel: MedLogAppViewModel = hiltViewModel()
    val recipientViewModel: CareRecipientsViewModel = hiltViewModel()
    val recipientState by recipientViewModel.uiState.collectAsStateWithLifecycle()

    // ── 首次运行成员门禁：没有任何家庭成员时，替代全部正常内容 ─────────────
    when {
        recipientState.isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return
        }
        recipientState.recipients.isEmpty() -> {
            CareRecipientGateScreen()
            return
        }
        recipientState.activeRecipientId == 0L -> {
            // 需求 3：成员存在但未选中时，ViewModel 会自动选中第一位；等待切换完成
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            return
        }
    }

    val startDestState by appViewModel.startDestination.collectAsStateWithLifecycle()
    // DataStore 加载期间显示居中加载指示器；捕获到本地 val 以消除后续 !! 需求
    val startDest = startDestState ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 响应快捷方式"添加药品"intent
    LaunchedEffect(openAddMedication) {
        if (openAddMedication) {
            navController.navigate(Route.AddMedication())
        }
    }

    val navigateToTopLevel: (TopLevelDestination) -> Unit = remember(navController) {
        { dest ->
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // ── 根据功能开关过滤可见目的地（enableSymptomDiary 关闭时隐藏「记录」Tab）─────────
    val featureFlags by appViewModel.featureFlags.collectAsStateWithLifecycle()
    val enabledDestinations = remember(featureFlags) {
        visibleTopLevelDestinations(
            enableSymptomDiary = featureFlags.enableSymptomDiary,
            enableDrugDatabase = featureFlags.enableDrugDatabase,
            enableHealthModule = featureFlags.enableHealthModule,
        )
    }
    // Decide whether to show the main navigation wrapper
    // Welcome 屏不展示导航栏
    val isOnWelcome = currentDestination?.hasRoute(Route.Welcome::class) == true
    val showMainNav = !isOnWelcome &&
        (
            currentDestination == null ||
                TOP_LEVEL_DESTINATIONS.any { currentDestination.hasRoute(it.route::class) }
            )

    if (showMainNav) {
        MedLogNavigationWrapper(
            currentDestination = currentDestination,
            navigateToTopLevel = navigateToTopLevel,
            destinations = enabledDestinations,
            recipients = recipientState.recipients,
            activeRecipientId = recipientState.activeRecipientId,
            onSelectRecipient = { id -> recipientViewModel.onAction(CareRecipientsUiAction.SetActive(id)) },
            onManageRecipients = { navController.navigate(Route.SettingsRecipients) },
        ) {
            MedLogNavHost(
                navController = navController,
                startDest = startDest,
                catalogEnabled = featureFlags.enableDrugDatabase,
                diaryAvailable = featureFlags.enableSymptomDiary,
                familyMembers = recipientState.recipients,
                activeRecipientId = recipientState.activeRecipientId,
                onSelectFamilyMember = { id -> recipientViewModel.onAction(CareRecipientsUiAction.SetActive(id)) },
                onManageFamilyMembers = { navController.navigate(Route.SettingsRecipients) },
            )
        }
    } else {
        MedLogNavHost(
            navController = navController,
            startDest = startDest,
            catalogEnabled = featureFlags.enableDrugDatabase,
            familyMembers = recipientState.recipients,
            activeRecipientId = recipientState.activeRecipientId,
            onSelectFamilyMember = { id -> recipientViewModel.onAction(CareRecipientsUiAction.SetActive(id)) },
            onManageFamilyMembers = { navController.navigate(Route.SettingsRecipients) },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun MedLogNavHost(
    navController: androidx.navigation.NavHostController,
    startDest: Route,
    catalogEnabled: Boolean,
    diaryAvailable: Boolean = false,
    familyMembers: List<CareRecipient> = emptyList(),
    activeRecipientId: Long = 0L,
    onSelectFamilyMember: (Long) -> Unit = {},
    onManageFamilyMembers: () -> Unit = {},
) {
    val motionScheme = MaterialTheme.motionScheme
    val navFadeIn = fadeIn(animationSpec = motionScheme.fastEffectsSpec())
    val navFadeOut = fadeOut(animationSpec = motionScheme.fastEffectsSpec())
    fun materialSharedAxisX(forward: Boolean) = slideInHorizontally(
        animationSpec = motionScheme.defaultSpatialSpec<IntOffset>(),
        initialOffsetX = { width -> if (forward) width else -width / 4 },
    ) + navFadeIn
    fun materialSharedAxisXOut(forward: Boolean) = slideOutHorizontally(
        animationSpec = motionScheme.defaultSpatialSpec<IntOffset>(),
        targetOffsetX = { width -> if (forward) width else -width / 4 },
    ) + navFadeOut

    NavHost(
        navController = navController,
        startDestination = startDest,
        // 顶层 Tab 切换：淡入淡出
        enterTransition = { navFadeIn },
        exitTransition = { navFadeOut },
        // 深层导航：水平滑动
        popEnterTransition = {
            materialSharedAxisX(forward = false)
        },
        popExitTransition = {
            materialSharedAxisXOut(forward = true)
        },
    ) {
        // ── 欢迎引导（首次启动）───────────────────────────
        composable<Route.Welcome>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            WelcomeScreen(
                onFinished = {
                    navController.navigate(Route.Home) {
                        popUpTo<Route.Welcome> { inclusive = true }
                    }
                },
            )
        }
        // ── 顶层目的地（Tab 切换：只淡入淡出）──────────────
        composable<Route.Home>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            HomeScreen(
                onAddMedication = { navController.navigate(Route.AddMedication()) },
                onMedicationClick = { id -> navController.navigate(Route.MedDetail(id)) },
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenCareTasks = { navController.navigate(Route.CareTasks) },
                onOpenTodos = { navController.navigate(Route.Todos) },
                onOpenCareNotes = { navController.navigate(Route.Records) },
                onOpenCarePeople = { navController.navigate(Route.CarePeople) },
                onCreateTodo = { navController.navigate(Route.TodoEditor()) },
                familyMembers = familyMembers,
                activeRecipientId = activeRecipientId,
                onSelectFamilyMember = onSelectFamilyMember,
                onManageFamilyMembers = onManageFamilyMembers,
            )
        }
        composable<Route.History>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            HistoryScreen(onOpenSettings = { navController.navigate(Route.Settings) })
        }
        composable<Route.MyMedications>(enterTransition = { navFadeIn }, exitTransition = { navFadeOut }) {
            com.driezy.medlog.feature.medications.list.MyMedicationsScreen(
                onAdd = { navController.navigate(Route.AddMedication()) },
                onOpen = { navController.navigate(Route.MedDetail(it)) },
                onCatalog = if (catalogEnabled) ({ navController.navigate(Route.Drugs) }) else null,
                onSettings = { navController.navigate(Route.Settings) },
            )
        }
        composable<Route.Drugs>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            DrugsScreen(
                onBack = { navController.popBackStack() },
                onAddCustomDrug = { navController.navigate(Route.AddMedication()) },
                onOpenSettings = { navController.navigate(Route.Settings) },
                onDrugSelect = { drug ->
                    navController.navigate(
                        Route.AddMedication(
                            drugName = drug.name,
                            drugCategory = drug.category,
                        ),
                    )
                },
            )
        }
        composable<Route.Records>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            RecordsScreen(
                diaryAvailable = diaryAvailable,
                onOpenCareNote = { id -> navController.navigate(Route.CareNoteEditor(id)) },
                onAddCareNote = { navController.navigate(Route.CareNoteEditor()) },
            )
        }
        composable<Route.Health>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            HealthScreen(
                onOpenSettings = { navController.navigate(Route.Settings) },
                onNavigateToBpx1Settings = { navController.navigate(Route.SettingsBpx1) },
            )
        }
        composable<Route.Settings>(
            enterTransition = { navFadeIn },
            exitTransition = { navFadeOut },
        ) {
            SettingsScreen(
                onNavigateToWelcome = {
                    navController.navigate(Route.Welcome) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = false
                        }
                    }
                },
                onNavigateToAppearanceSettings = { navController.navigate(Route.SettingsAppearance) },
                onNavigateToReminderSettings = { navController.navigate(Route.SettingsReminders) },
                onNavigateToModuleSettings = { navController.navigate(Route.SettingsModules) },
                onNavigateToIntelligenceSettings = { navController.navigate(Route.SettingsIntelligence) },
                onNavigateToBpx1Settings = { navController.navigate(Route.SettingsBpx1) },
                onNavigateToWidgetSettings = { navController.navigate(Route.SettingsWidgets) },
                onNavigateToDataSettings = { navController.navigate(Route.SettingsData) },
                onNavigateToMemberSettings = { navController.navigate(Route.SettingsRecipients) },
            )
        }
        composable<Route.SettingsAppearance>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsReminders>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            ReminderSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsModules>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            ModuleSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsIntelligence>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            IntelligenceSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToCloudApiSettings = { navController.navigate(Route.SettingsCloudApi) },
            )
        }
        composable<Route.SettingsCloudApi>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CloudApiSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsBpx1>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            Bpx1DeviceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsWidgets>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            WidgetSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.SettingsData>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            DataSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToWelcome = {
                    navController.navigate(Route.Welcome) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = false
                        }
                    }
                },
            )
        }
        composable<Route.SettingsRecipients>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CareRecipientsScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.MedDetail>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.MedDetail = backStackEntry.toRoute()
            MedicationDetailScreen(
                medicationId = route.medicationId,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Route.AddMedication(id)) },
                onQuickAddNote = {
                    navController.navigate(
                        Route.CareNoteEditor(
                            prelinkType = CareNoteTargetType.MEDICATION,
                            prelinkId = route.medicationId,
                        ),
                    )
                },
            )
        }
        composable<Route.AddMedication>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.AddMedication = backStackEntry.toRoute()
            AddMedicationScreen(
                medicationId = route.medicationId.takeIf { it != -1L },
                drugName = route.drugName.ifEmpty { null },
                drugCategory = route.drugCategory.ifEmpty { null },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable<Route.CareTasks>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CareTasksScreen(
                onAdd = { navController.navigate(Route.CareTaskEditor()) },
                onOpen = { id -> navController.navigate(Route.CareTaskDetail(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.CareTaskDetail>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.CareTaskDetail = backStackEntry.toRoute()
            CareTaskDetailScreen(
                careTaskId = route.careTaskId,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Route.CareTaskEditor(id)) },
                onQuickAddNote = {
                    navController.navigate(
                        Route.CareNoteEditor(
                            prelinkType = CareNoteTargetType.CARE_TASK,
                            prelinkId = route.careTaskId,
                        ),
                    )
                },
            )
        }
        composable<Route.CareTaskEditor>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.CareTaskEditor = backStackEntry.toRoute()
            CareTaskEditorScreen(
                careTaskId = route.careTaskId.takeIf { it != -1L },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable<Route.Todos>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CareTodosScreen(
                onAdd = { navController.navigate(Route.TodoEditor()) },
                onOpen = { id -> navController.navigate(Route.TodoEditor(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.TodoEditor>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.TodoEditor = backStackEntry.toRoute()
            CareTodoEditorScreen(
                todoId = route.todoId.takeIf { it != -1L },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onQuickAddNote = {
                    val todoId = route.todoId
                    if (todoId != -1L) {
                        navController.navigate(
                            Route.CareNoteEditor(
                                prelinkType = CareNoteTargetType.TODO,
                                prelinkId = todoId,
                            ),
                        )
                    }
                },
            )
        }
        composable<Route.CareNotes>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CareNotesScreen(
                onAdd = { navController.navigate(Route.CareNoteEditor()) },
                onOpen = { id -> navController.navigate(Route.CareNoteEditor(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.CarePeople>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) {
            CarePeopleScreen(onBack = { navController.popBackStack() })
        }
        composable<Route.CareNoteEditor>(
            enterTransition = { materialSharedAxisX(forward = true) },
            exitTransition = { navFadeOut },
            popEnterTransition = { materialSharedAxisX(forward = false) },
            popExitTransition = { materialSharedAxisXOut(forward = true) },
        ) { backStackEntry ->
            val route: Route.CareNoteEditor = backStackEntry.toRoute()
            CareNoteEditorScreen(
                noteId = route.noteId.takeIf { it != -1L },
                prelinkType = route.prelinkType.takeIf { it.isNotEmpty() },
                prelinkId = route.prelinkId.takeIf { it != -1L },
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}
