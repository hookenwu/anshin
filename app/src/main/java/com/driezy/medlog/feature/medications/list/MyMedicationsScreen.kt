package com.driezy.medlog.feature.medications.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.repository.MedicationSortOrder
import com.driezy.medlog.ui.components.*
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import com.driezy.medlog.ui.util.doseDisplayText
import com.driezy.medlog.ui.util.formatDose
import com.driezy.medlog.ui.util.timePeriodsLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MyMedicationsScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onCatalog: (() -> Unit)?,
    onSettings: () -> Unit,
    onReconcile: () -> Unit = {},
    viewModel: MyMedicationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkedInToday by viewModel.checkedInToday.collectAsStateWithLifecycle()
    MyMedicationsContent(
        state = state,
        onAdd = onAdd,
        onOpen = onOpen,
        onCatalog = onCatalog,
        onSettings = onSettings,
        onRetry = viewModel::retry,
        onSortOrderChange = viewModel::onSortOrderChange,
        onBatchStatusChange = { ids, kind, date, reason -> viewModel.applyStatus(ids.toList(), kind, date, reason) },
        onReconcile = onReconcile,
        checkedInToday = checkedInToday,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyMedicationsContent(
    state: MyMedicationsState,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onCatalog: (() -> Unit)?,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onSortOrderChange: (MedicationSortOrder) -> Unit,
    /** 批量停用 / 恢复 / 归档：`(选中 id, kind, 生效日期, 原因)`（docs/medication-status-spec.md §10）。 */
    onBatchStatusChange: (Set<Long>, String, LocalDate, String?) -> Unit = { _, _, _, _ -> },
    /** 入口：导航到「今日用药核对」子路由（docs/medication-daily-reconcile.md T4/T6）。 */
    onReconcile: () -> Unit = {},
    /** 「今天尚未核对」提示：读 `lastUserCheckedDate`（T6）。 */
    checkedInToday: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var pendingKind by remember { mutableStateOf<String?>(null) }
    val visible = remember(state.medications, archived) { state.medications.filter { it.isArchived == archived } }

    fun toggleSelection(id: Long) {
        if (id in selected) selected.remove(id) else selected.add(id)
    }

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.my_medications)) },
        actions = buildList {
            if (onCatalog != null) {
                add(
                    TopBarAction(
                        "catalog",
                        stringResource(R.string.medication_catalog),
                        MedLogIcons.Search,
                        TopBarActionPriority.Primary,
                    ),
                )
            }
            add(
                TopBarAction(
                    "settings",
                    stringResource(R.string.settings_action_open),
                    MedLogIcons.Settings,
                    TopBarActionPriority.Secondary,
                ),
            )
        },
        chromeState = ScreenChromeState(
            isLoading = state.loading,
            fab = ScreenFab("add", stringResource(R.string.add_title_new), MedLogIcons.Add),
        ),
        onChromeAction = {
            when (it) {
                "add" -> onAdd()
                "catalog" -> onCatalog?.invoke()
                "settings" -> onSettings()
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = MedLogSpacing.ScreenContentWithFab,
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
        ) {
            item(key = "filters", contentType = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                        FilterChip(
                            selected = !archived,
                            onClick = { archived = false },
                            label = { Text(stringResource(R.string.medication_active)) },
                        )
                        FilterChip(
                            selected = archived,
                            onClick = { archived = true },
                            label = { Text(stringResource(R.string.medication_archived)) },
                        )
                        FilterChip(
                            selected = selectionMode,
                            onClick = {
                                selectionMode = !selectionMode
                                if (!selectionMode) selected.clear()
                            },
                            label = { Text(stringResource(R.string.medication_batch)) },
                        )
                        FilterChip(
                            selected = false,
                            onClick = onReconcile,
                            label = { Text(stringResource(R.string.medication_reconcile_entry)) },
                        )
                    }
                    Text(
                        stringResource(
                            if (checkedInToday) {
                                R.string.medication_reconcile_checked_hint
                            } else {
                                R.string.medication_reconcile_pending_hint
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                    ) {
                        MedicationSortOrder.entries.forEach { order ->
                            FilterChip(
                                selected = state.sortOrder == order,
                                onClick = { onSortOrderChange(order) },
                                label = { Text(stringResource(order.labelRes)) },
                            )
                        }
                    }
                    if (selectionMode) {
                        Text(
                            stringResource(R.string.medication_batch_selected, selected.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.medication_batch_select_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.failed) {
                item(key = "error") {
                    MedicationMessageCard(
                        stringResource(R.string.medication_load_failed),
                        isError = true,
                        onRetry = onRetry,
                    )
                }
            }
            if (!state.loading && !state.failed && visible.isEmpty()) {
                item(key = "empty") {
                    MedicationMessageCard(
                        stringResource(
                            if (archived) R.string.medication_archived_empty else R.string.medication_active_empty,
                        ),
                    )
                }
            }
            items(visible, key = { it.id }, contentType = { "medication" }) { med ->
                Card(
                    onClick = { if (selectionMode) toggleSelection(med.id) else onOpen(med.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.padding(MedLogSpacing.Large),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = med.id in selected,
                                onCheckedChange = { toggleSelection(med.id) },
                            )
                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                        ) {
                            Text(med.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    "${med.doseDisplayText()} · ${scheduleLabel(med)}",
                                    med.offsetRowText(),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            statusBadgeLabel(state.statusKinds[med.id])?.let { badge ->
                                Text(
                                    badge,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            med.stock?.let { stock ->
                                Text(
                                    stringResource(R.string.medication_stock_value, stock.formatDose(), med.doseUnit),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (stock <= (med.refillThreshold ?: 0.0)) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                stringResource(
                                    if (archived) {
                                        R.string.medication_archived_hint
                                    } else {
                                        R.string.medication_manage_hint
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (selectionMode && selected.isNotEmpty()) {
                item(key = "batch_actions", contentType = "batch_actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                    ) {
                        Button(onClick = { pendingKind = MedicationStatusKind.PAUSE }) {
                            Text(stringResource(R.string.medication_batch_pause))
                        }
                        Button(onClick = { pendingKind = MedicationStatusKind.RESUME }) {
                            Text(stringResource(R.string.medication_batch_resume))
                        }
                        OutlinedButton(onClick = { pendingKind = MedicationStatusKind.DISCONTINUE }) {
                            Text(stringResource(R.string.medication_batch_discontinue))
                        }
                    }
                }
            }
        }
    }

    pendingKind?.let { kind ->
        BatchStatusDialog(
            today = today,
            onConfirm = { date, reason ->
                onBatchStatusChange(selected.toSet(), kind, date, reason)
                pendingKind = null
                selected.clear()
            },
            onDismiss = { pendingKind = null },
        )
    }
}

@Composable
private fun statusBadgeLabel(kind: String?): String? = when (kind) {
    MedicationStatusKind.PAUSE -> stringResource(R.string.medication_status_paused)
    MedicationStatusKind.DISCONTINUE -> stringResource(R.string.medication_status_archived)
    else -> null
}

@Composable
private fun BatchStatusDialog(today: LocalDate, onConfirm: (LocalDate, String?) -> Unit, onDismiss: () -> Unit) {
    var dateText by remember { mutableStateOf(today.toString()) }
    var reason by remember { mutableStateOf("") }
    val parsed = EffectiveDateInput.parse(dateText, today)
    val valid = parsed != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.medication_batch)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(stringResource(R.string.medication_batch_effective_date)) },
                    isError = !valid,
                    singleLine = true,
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.medication_batch_future_date_rejected),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.medication_batch_reason)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { parsed?.let { onConfirm(it, reason.ifBlank { null }) } },
            ) {
                Text(stringResource(R.string.medication_batch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.medication_batch_cancel)) }
        },
    )
}

@Composable
private fun scheduleLabel(medication: Medication): String {
    if (medication.isPRN) return stringResource(R.string.medication_as_needed)
    if (medication.intervalHours > 0) {
        return pluralStringResource(
            R.plurals.medication_every_hours,
            medication.intervalHours,
            medication.intervalHours,
        )
    }
    val frequency = when (medication.frequencyType) {
        "interval" -> pluralStringResource(
            R.plurals.detail_freq_interval,
            medication.frequencyInterval,
            medication.frequencyInterval,
        )
        "specific_days" -> medication.frequencyDays.split(",")
            .mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }
            .joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        else -> stringResource(R.string.detail_freq_daily)
    }
    return "$frequency · ${medication.scheduleTimeText()}"
}

/**
 * 列表行里的「时间」部分：优先显示作息时段（如「午餐后、晚餐后」），
 * 没有时段（精确时间 / 未识别）时才回落具体钟点。
 */
@Composable
private fun Medication.scheduleTimeText(): String {
    val periodLabel = timePeriodsLabel(timePeriod)
    if (periodLabel != null) return periodLabel
    return reminderTimes.replace(",", ", ")
}

/**
 * 列表行里的「提醒偏移」片段（B12）：偏移为 0 时返回 null —— 行里**什么都不多出来**。
 * 符号与单位都写在资源里（`%1$d` 只接收绝对值），四个 locale 各自决定 `+`/`−` 与量词位置，
 * 代码里不出现硬编码的正负号。
 */
@Composable
private fun Medication.offsetRowText(): String? {
    if (offsetMinutes == 0) return null
    return offsetRowSegment(
        offsetMinutes = offsetMinutes,
        laterFormat = stringResource(R.string.medication_row_offset_later),
        earlierFormat = stringResource(R.string.medication_row_offset_earlier),
    )
}

internal fun offsetRowSegment(offsetMinutes: Int, laterFormat: String, earlierFormat: String): String? = when {
    offsetMinutes > 0 -> String.format(Locale.ROOT, laterFormat, offsetMinutes)
    offsetMinutes < 0 -> String.format(Locale.ROOT, earlierFormat, -offsetMinutes)
    else -> null
}
