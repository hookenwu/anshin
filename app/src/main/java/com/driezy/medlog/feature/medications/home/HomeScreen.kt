package com.driezy.medlog.feature.medications.home

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.feature.caretasks.careTaskCategoryLabel
import com.driezy.medlog.ui.components.FamilyMemberPickerDialog
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.MedicationMessageCard
import com.driezy.medlog.ui.components.RefreshWhileVisible
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.components.ScreenTopBarSize
import com.driezy.medlog.ui.components.TopBarAction
import com.driezy.medlog.ui.components.TopBarActionPriority
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import com.driezy.medlog.ui.theme.emphasizedTypography
import com.driezy.medlog.ui.util.displayName
import com.driezy.medlog.ui.utils.MedLogHapticEffect
import com.driezy.medlog.ui.utils.rememberMedLogHaptics
import kotlinx.coroutines.delay
import java.util.*

/** 列表项交错入场动画的逐项延迟（毫秒） */
internal const val STAGGER_DELAY_MS = 30L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onAddMedication: () -> Unit,
    onMedicationClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCareTasks: () -> Unit = {},
    familyMembers: List<CareRecipient> = emptyList(),
    activeRecipientId: Long = 0L,
    onSelectFamilyMember: (Long) -> Unit = {},
    onManageFamilyMembers: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    RefreshWhileVisible { viewModel.onAction(HomeUiAction.RefreshTime) }

    @Suppress("LocalContextResourcesRead") // resources 在组合时捕获，用于 LaunchedEffect 中的 plurals
    val importResources = context.resources
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeUiEffect.DoseSaved -> {
                    val message = importResources.getString(
                        if (effect.change.after ==
                            null
                        ) {
                            R.string.dose_record_removed
                        } else {
                            R.string.dose_record_saved
                        },
                    )
                    val result = snackbarHostState.showSnackbar(
                        message,
                        actionLabel = importResources.getString(R.string.home_snackbar_undo),
                    )
                    if (result ==
                        SnackbarResult.ActionPerformed
                    ) {
                        viewModel.onAction(HomeUiAction.RestoreDose(effect.change))
                    }
                }
                is HomeUiEffect.Failed -> snackbarHostState.showSnackbar(
                    importResources.getString(R.string.dose_record_failed),
                )
                is HomeUiEffect.ImportSucceeded -> {
                    val message = importResources.getQuantityString(
                        R.plurals.qr_import_success,
                        effect.count,
                        effect.count,
                    )
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }
    HomeContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        onAddMedication = onAddMedication,
        onMedicationClick = onMedicationClick,
        onOpenSettings = onOpenSettings,
        onOpenCareTasks = onOpenCareTasks,
        familyMembers = familyMembers,
        activeRecipientId = activeRecipientId,
        onSelectFamilyMember = onSelectFamilyMember,
        onManageFamilyMembers = onManageFamilyMembers,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeContent(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onAction: (HomeUiAction) -> Unit,
    onAddMedication: () -> Unit,
    onMedicationClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCareTasks: () -> Unit,
    familyMembers: List<CareRecipient> = emptyList(),
    activeRecipientId: Long = 0L,
    onSelectFamilyMember: (Long) -> Unit = {},
    onManageFamilyMembers: () -> Unit = {},
) {
    val performHaptic = rememberMedLogHaptics()
    var overlay by remember { mutableStateOf<ScreenOverlay?>(null) }
    var memberPickerOpen by remember { mutableStateOf(false) }
    fun toggleDose(item: MedicationWithStatus) {
        if (item.doseKey in uiState.savingDoses) return
        performHaptic(MedLogHapticEffect.CONFIRM)
        onAction(HomeUiAction.ToggleDose(item))
    }

    fun skipDose(item: MedicationWithStatus) {
        if (item.doseKey in uiState.savingDoses) return
        performHaptic(MedLogHapticEffect.CONFIRM)
        onAction(HomeUiAction.SkipDose(item))
    }

    fun togglePrnDose(item: MedicationWithStatus) = toggleDose(item)

    val lowStockItems = remember(uiState.items) {
        uiState.items.distinctBy { it.medication.id }.filter { item ->
            val stock = item.medication.stock ?: return@filter false
            val threshold = item.medication.refillThreshold ?: return@filter false
            stock <= threshold
        }
    }

    // 首页顶栏常驻成员切换入口（手机端无需打开抽屉即可切换）
    val memberActions = if (familyMembers.isNotEmpty()) {
        listOf(
            TopBarAction(
                id = "family",
                label = stringResource(R.string.recipients_switcher_action),
                icon = MedLogIcons.VerifiedUser,
                priority = TopBarActionPriority.Primary,
            ),
        )
    } else {
        emptyList<TopBarAction>()
    }

    MedLogScreenScaffold(
        topBarSize = ScreenTopBarSize.Compact,
        title = {
            Column {
                Text(stringResource(R.string.home_title), style = MaterialTheme.emphasizedTypography.titleLarge)
                Text(
                    todayDateString(uiState.today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = memberActions + listOf(
            TopBarAction(
                id = "care_tasks",
                label = stringResource(R.string.care_task_nav_action),
                icon = MedLogIcons.Favorite,
                priority = TopBarActionPriority.Secondary,
            ),
            TopBarAction(
                id = "qr",
                label = stringResource(R.string.home_share_qr_cd),
                icon = MedLogIcons.QrCode2,
                priority = TopBarActionPriority.Primary,
            ),
            TopBarAction(
                id = "group",
                label = if (uiState.groupByTime) {
                    stringResource(R.string.home_group_toggle_by_category)
                } else {
                    stringResource(R.string.home_group_toggle_by_time)
                },
                icon = if (uiState.groupByTime) MedLogIcons.Category else MedLogIcons.AccessTime,
                priority = TopBarActionPriority.Secondary,
            ),
            TopBarAction(
                id = "settings",
                label = stringResource(R.string.settings_action_open),
                icon = MedLogIcons.Settings,
                priority = TopBarActionPriority.Secondary,
            ),
        ),
        chromeState = ScreenChromeState(
            isLoading = uiState.isLoading,
            fab = ScreenFab(
                id = "add",
                label = stringResource(R.string.home_fab_add),
                icon = MedLogIcons.Add,
            ),
        ),
        snackbarHostState = snackbarHostState,
        onChromeAction = { id ->
            when (id) {
                "add" -> onAddMedication()
                "family" -> memberPickerOpen = true
                "qr" -> {
                    overlay = ScreenOverlay.Custom(id = "home:qr") {
                        MedicationQrDialog(
                            items = uiState.items,
                            onDismiss = { overlay = null },
                            generateExportUri = { uiState.exportUri },
                            onQrScanned = {
                                performHaptic(MedLogHapticEffect.CONFIRM)
                                onAction(HomeUiAction.QrScanned(it))
                            },
                        )
                    }
                }
                "group" -> {
                    performHaptic(MedLogHapticEffect.TOGGLE)
                    onAction(HomeUiAction.ToggleGrouping)
                }
                "settings" -> onOpenSettings()
                "care_tasks" -> onOpenCareTasks()
            }
        },
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = MedLogSpacing.ScreenContentDefault,
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            if (uiState.savingDoses.isNotEmpty()) {
                item(key = "saving") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            if (uiState.errorMessage != null) {
                item(key = "loadError") {
                    MedicationMessageCard(
                        stringResource(R.string.dose_load_failed),
                        isError = true,
                        onRetry = { onAction(HomeUiAction.RefreshTime) },
                    )
                }
            }
            item(key = "homeHero", contentType = "homeHero") {
                HomeHero(
                    presentation = uiState.heroPresentation,
                    style = uiState.homeHeroStyle,
                    currentStreak = uiState.currentStreak,
                    overallHandled = uiState.overallHandled,
                    overallTotal = uiState.overallTotal,
                    onTakeNext = ::toggleDose,
                    onSkipNext = ::skipDose,
                    onViewDetails = { onMedicationClick(it.medication.id) },
                    onAddMedication = onAddMedication,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── 低库存警告 banner ──────────────────────────────
            if (lowStockItems.isNotEmpty()) {
                item {
                    LowStockBanner(
                        medications = lowStockItems.map {
                            it.medication.displayName() to
                                ((it.medication.stock ?: 0.0) to it.medication.doseUnit)
                        },
                    )
                }
            }
            // ── 药品相互作用警告 ───────────────────────────
            if (uiState.interactions.isNotEmpty()) {
                item {
                    InteractionBannerCard(
                        interactions = uiState.interactions,
                    )
                }
            }
            // ── 时间轴筛选 chips（全部 / 用药 / 照护 / 照护子类）──────
            if (uiState.overallTotal > 0) {
                item(key = "todayFilters", contentType = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = MedLogSpacing.Tiny),
                        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                    ) {
                        FilterChip(
                            selected = uiState.todayFilter == TodayFilter.All,
                            onClick = { onAction(HomeUiAction.SetTodayFilter(TodayFilter.All)) },
                            label = { Text(stringResource(R.string.home_filter_all)) },
                            modifier = Modifier.testTag("todayFilterAll"),
                        )
                        FilterChip(
                            selected = uiState.todayFilter == TodayFilter.Medication,
                            onClick = { onAction(HomeUiAction.SetTodayFilter(TodayFilter.Medication)) },
                            label = { Text(stringResource(R.string.home_filter_medication)) },
                            modifier = Modifier.testTag("todayFilterMedication"),
                        )
                        FilterChip(
                            selected = uiState.todayFilter == TodayFilter.CareTask,
                            onClick = { onAction(HomeUiAction.SetTodayFilter(TodayFilter.CareTask)) },
                            label = { Text(stringResource(R.string.home_filter_care)) },
                            modifier = Modifier.testTag("todayFilterCare"),
                        )
                        uiState.careCategoriesPresent.forEach { category ->
                            FilterChip(
                                selected = uiState.todayFilter == TodayFilter.CareCategory(category),
                                onClick = {
                                    onAction(HomeUiAction.SetTodayFilter(TodayFilter.CareCategory(category)))
                                },
                                label = { Text(careTaskCategoryLabel(category)) },
                                modifier = Modifier.testTag("todayFilterCareCategory:$category"),
                            )
                        }
                    }
                }
            }

            if (uiState.overallTotal > 0) {
                item(key = "todayPlanHeader", contentType = "header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MedLogSpacing.Medium, bottom = MedLogSpacing.Tiny),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.home_hero_plan_title),
                            style = MaterialTheme.emphasizedTypography.titleLarge,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.home_hero_plan_count,
                                uiState.overallTotal,
                                uiState.overallTotal,
                            ),
                            modifier = Modifier.testTag("homePlanCount"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 统一时间轴（用药 + 照护事项同一渲染器，可按时段或分类分组）──
            val focusedPlanItem = uiState.heroPresentation.nextPendingItem
            val focusedDoseKey = focusedPlanItem?.doseKey

            // 仅当英雄卡确实聚焦某剂药时才从时间轴剔除该剂；照护事项不受影响。
            fun isFocusedMedication(item: TodayItem): Boolean =
                focusedDoseKey != null && item.medication?.doseKey == focusedDoseKey
            if (uiState.groupByTime) {
                val taskGroups = listOf(
                    "now" to uiState.timelineNowItems,
                    "later" to uiState.timelineLaterItems,
                ).map { (key, groupItems) ->
                    key to groupItems.filterNot { isFocusedMedication(it) }
                }.filter { (_, groupItems) -> groupItems.isNotEmpty() }
                taskGroups.forEach { (key, groupItems) ->
                    item(key = "task_group_$key", contentType = "taskGroup") {
                        val groupTitle = if (key == "now") {
                            stringResource(R.string.home_now_group_title)
                        } else {
                            stringResource(R.string.home_later_group_title)
                        }
                        TodayTaskGroupCard(
                            title = groupTitle,
                            subtitle = if (key == "now") {
                                stringResource(R.string.home_now_group_body)
                            } else {
                                stringResource(R.string.home_later_group_body)
                            },
                            icon = if (key == "now") MedLogIcons.CheckCircle else MedLogIcons.AccessTime,
                            items = groupItems,
                            onAction = onAction,
                            onMedicationClick = onMedicationClick,
                            modifier = Modifier.animateItem(),
                            autoCollapse = uiState.autoCollapseCompletedGroups,
                        )
                    }
                }
            } else {
                // 分类分组：扁平卡片列表（用药与照护事项按各自 category 归并）
                uiState.timelineCategoryGroups.forEach { (category, groupItems) ->
                    val visibleItems = groupItems.filterNot { isFocusedMedication(it) }
                    if (category.isNotBlank() && visibleItems.isNotEmpty()) {
                        item(key = "header_$category", contentType = "header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                            ) {
                                Text(
                                    text = if (category == HomeUiState.UNCATEGORIZED_KEY) {
                                        stringResource(R.string.category_other)
                                    } else {
                                        category
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        visibleItems,
                        key = { _, it -> it.listKey },
                    ) { idx, item ->
                        val motionScheme = MaterialTheme.motionScheme
                        val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
                        var visible by remember(item.listKey) { mutableStateOf(false) }
                        LaunchedEffect(item.listKey, animationsEnabled) {
                            if (animationsEnabled) {
                                delay(idx * STAGGER_DELAY_MS) // 基于组内索引，而非全局，避免底部首次出现延迟
                            }
                            visible = true
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(motionScheme.defaultEffectsSpec()) +
                                slideInVertically(motionScheme.defaultSpatialSpec()) { it / 4 },
                        ) {
                            TodayTimelineRow(
                                item = item,
                                flatStyle = false,
                                onAction = onAction,
                                onMedicationClick = onMedicationClick,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            // ── PRN 按需用药区域 ───────────────────────────────
            if (uiState.prnItems.isNotEmpty()) {
                item(key = "prnSection", contentType = "prnSection") {
                    PRNSectionCard(
                        items = uiState.prnItems,
                        onToggleTaken = ::togglePrnDose,
                        onClick = onMedicationClick,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    val importOverlay = when {
        uiState.importPreview != null -> ScreenOverlay.Custom(id = "home:import-preview") {
            ImportPreviewDialog(
                plan = requireNotNull(uiState.importPreview),
                onMerge = {
                    onAction(
                        HomeUiAction.ConfirmImport(com.driezy.medlog.feature.medications.application.ImportMode.MERGE),
                    )
                },
                onReplace = {
                    onAction(
                        HomeUiAction.ConfirmImport(
                            com.driezy.medlog.feature.medications.application.ImportMode.REPLACE,
                        ),
                    )
                },
                onDismiss = { onAction(HomeUiAction.ClearImportPreview) },
            )
        }
        uiState.importError != null -> ScreenOverlay.Confirm(
            id = "home:import-error",
            title = stringResource(R.string.qr_scan_title),
            body = stringResource(R.string.qr_invalid),
            confirmLabel = stringResource(R.string.home_close),
            dismissLabel = stringResource(R.string.home_close),
        )
        else -> null
    }
    ScreenOverlayHost(
        overlay = overlay ?: importOverlay,
        onConfirm = { descriptor, _ ->
            if (descriptor.id == "home:import-error") onAction(HomeUiAction.ClearImportPreview)
        },
        onDismiss = {
            overlay = null
            onAction(HomeUiAction.ClearImportPreview)
        },
    )

    if (memberPickerOpen) {
        FamilyMemberPickerDialog(
            recipients = familyMembers,
            activeRecipientId = activeRecipientId,
            onSelect = { id ->
                memberPickerOpen = false
                onSelectFamilyMember(id)
            },
            onManage = {
                memberPickerOpen = false
                onManageFamilyMembers()
            },
            onDismiss = { memberPickerOpen = false },
        )
    }
}
