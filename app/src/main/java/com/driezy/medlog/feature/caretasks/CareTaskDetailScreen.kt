package com.driezy.medlog.feature.caretasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.HealthType
import com.driezy.medlog.feature.carenotes.RelatedNotesSection
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.RefreshWhileVisible
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.components.TopBarAction
import com.driezy.medlog.ui.components.TopBarActionPriority
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import com.driezy.medlog.ui.util.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 照护事项详情页 Route：加载任务并集合成员作用域日志，命令交给 ViewModel → UseCase。
 */
@Composable
fun CareTaskDetailScreen(
    careTaskId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: CareTaskDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_task_load_failed)
    val recordedMessage = stringResource(R.string.care_task_record_saved)
    val duplicateMessage = stringResource(R.string.care_task_record_duplicate)

    RefreshWhileVisible { viewModel.onAction(CareTaskDetailUiAction.RefreshTime) }
    LaunchedEffect(careTaskId) { viewModel.onAction(CareTaskDetailUiAction.Load(careTaskId)) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareTaskDetailUiEffect.NavigateBack -> onBack()
                is CareTaskDetailUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
                CareTaskDetailUiEffect.MeasurementRecorded -> snackbarHostState.showSnackbar(recordedMessage)
                CareTaskDetailUiEffect.MeasurementDuplicate -> snackbarHostState.showSnackbar(duplicateMessage)
            }
        }
    }

    CareTaskDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onEdit = onEdit,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun CareTaskDetailContent(
    uiState: CareTaskDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onAction: (CareTaskDetailUiAction) -> Unit,
) {
    var overlay by remember { mutableStateOf<ScreenOverlay?>(null) }
    var recordFor by remember { mutableStateOf<CareTaskOccurrenceUi?>(null) }
    var completeFor by remember { mutableStateOf<CareTaskOccurrenceUi?>(null) }
    val task = uiState.task
    val actionEdit = stringResource(R.string.care_task_edit_action)
    val deleteTitle = stringResource(R.string.care_task_delete_title)
    val deleteBody = stringResource(R.string.care_task_delete_body)
    val deleteConfirm = stringResource(R.string.care_task_delete)
    val cancelLabel = stringResource(R.string.cancel)

    MedLogScreenScaffold(
        title = { Text(task?.title ?: stringResource(R.string.care_task_detail_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                MedLogIcon(MedLogIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = if (task != null) {
            listOf(TopBarAction("edit", actionEdit, MedLogIcons.Edit, TopBarActionPriority.Primary))
        } else {
            emptyList()
        },
        chromeState = ScreenChromeState(isLoading = uiState.isLoading),
        snackbarHostState = snackbarHostState,
        onChromeAction = { if (it == "edit") task?.let { current -> onEdit(current.id) } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = MedLogSpacing.ScreenContentDefault,
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            if (task != null) {
                item(key = "config") {
                    TaskConfigCard(
                        task = task,
                        onArchive = { onAction(CareTaskDetailUiAction.Archive) },
                        onDelete = {
                            overlay = ScreenOverlay.Confirm(
                                id = "delete",
                                title = deleteTitle,
                                body = deleteBody,
                                confirmLabel = deleteConfirm,
                                dismissLabel = cancelLabel,
                                isDanger = true,
                            )
                        },
                    )
                }
            }
            item(key = "today_header") {
                SectionHeader(stringResource(R.string.care_task_detail_today))
            }
            if (uiState.occurrences.isEmpty()) {
                item(key = "no_occurrence") {
                    Text(
                        stringResource(R.string.care_task_detail_no_occurrences),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                    )
                }
            }
            items(uiState.occurrences, key = { it.scheduledTimeMs }) { occurrence ->
                OccurrenceCard(
                    occurrence = occurrence,
                    completionMode = task?.completionMode ?: CareTaskCompletionMode.TOGGLE,
                    onAction = onAction,
                    onComplete = { onAction(CareTaskDetailUiAction.Complete(occurrence.scheduledTimeMs)) },
                    onCompleteDetails = { completeFor = occurrence },
                    onRecordMeasurement = { recordFor = occurrence },
                )
            }
            item(key = "history_header") {
                SectionHeader(stringResource(R.string.care_task_detail_history))
            }
            if (uiState.recentLogs.isEmpty()) {
                item(key = "history_empty") {
                    Text(
                        stringResource(R.string.care_task_detail_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                    )
                }
            }
            items(uiState.recentLogs, key = { it.id }) { log -> HistoryRow(log) }

            // ── 相关笔记（底部；空态不渲染任何东西，docs/care-notes.md §7）──
            if (uiState.relatedNotes.isNotEmpty()) {
                item(key = "relatedNotes", contentType = "relatedNotes") {
                    RelatedNotesSection(notes = uiState.relatedNotes)
                }
            }
        }
    }

    ScreenOverlayHost(
        overlay = overlay,
        onDismiss = { overlay = null },
        onConfirm = { resolved, _ ->
            if (resolved.id == "delete") onAction(CareTaskDetailUiAction.Delete)
        },
    )

    recordFor?.let { occurrence ->
        RecordMeasurementDialog(
            onDismiss = { recordFor = null },
            onConfirm = { type, value, secondary, notes ->
                onAction(
                    CareTaskDetailUiAction.RecordMeasurement(
                        scheduledTimeMs = occurrence.scheduledTimeMs,
                        type = type,
                        value = value,
                        secondaryValue = secondary,
                        notes = notes,
                    ),
                )
                recordFor = null
            },
        )
    }

    completeFor?.let { occurrence ->
        CompleteTaskDialog(
            onDismiss = { completeFor = null },
            onConfirm = { posture, notes ->
                onAction(
                    CareTaskDetailUiAction.Complete(
                        scheduledTimeMs = occurrence.scheduledTimeMs,
                        postureNote = posture,
                        notes = notes,
                    ),
                )
                completeFor = null
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Small),
    )
}

@Composable
private fun TaskConfigCard(task: com.driezy.medlog.data.model.CareTask, onArchive: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MedLogSpacing.Large),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(MedLogSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            Text(
                "${careTaskCategoryLabel(task.category)} · ${careTaskScheduleSummary(task)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (task.notes.isNotBlank()) {
                Text(task.notes, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                TextButton(onClick = onArchive) {
                    Text(
                        stringResource(
                            if (task.isArchived) R.string.care_task_unarchive else R.string.care_task_archive,
                        ),
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.care_task_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun OccurrenceCard(
    occurrence: CareTaskOccurrenceUi,
    completionMode: CareTaskCompletionMode,
    onAction: (CareTaskDetailUiAction) -> Unit,
    onComplete: () -> Unit,
    onCompleteDetails: () -> Unit,
    onRecordMeasurement: () -> Unit,
) {
    val scheduledMs = occurrence.scheduledTimeMs
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MedLogSpacing.Large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(MedLogSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            Text(
                occurrence.timeLabel.ifEmpty { stringResource(R.string.care_task_schedule_as_needed) },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(occurrenceStatusText(occurrence), style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (occurrence.status) {
                    CareTaskLogStatus.DONE, CareTaskLogStatus.SKIPPED -> {
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Undo(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_undo))
                        }
                    }
                    CareTaskLogStatus.IN_PROGRESS -> {
                        TextButton(onClick = onComplete) {
                            Text(stringResource(R.string.care_task_action_complete))
                        }
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Skip(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_skip))
                        }
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Undo(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_undo))
                        }
                    }
                    null -> {
                        if (completionMode == CareTaskCompletionMode.DURATION) {
                            TextButton(onClick = { onAction(CareTaskDetailUiAction.Start(scheduledMs)) }) {
                                Text(stringResource(R.string.care_task_action_start))
                            }
                        }
                        TextButton(onClick = onComplete) {
                            Text(stringResource(R.string.care_task_action_complete))
                        }
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Skip(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_skip))
                        }
                    }
                }
            }
            // T8：任何状态下都能补记过程数据（血氧 / 氧流量 / 次数）。
            TextButton(onClick = onRecordMeasurement) {
                Text(stringResource(R.string.care_task_record_measurement))
            }
            // 一键打卡是主路径；体位/备注是可选补充（已完成的记录同日可补记，见 T5 的编辑语义）。
            TextButton(onClick = onCompleteDetails) {
                Text(stringResource(R.string.care_task_action_posture_notes))
            }
        }
    }
}

@Composable
private fun occurrenceStatusText(occurrence: CareTaskOccurrenceUi): String = when (occurrence.status) {
    CareTaskLogStatus.DONE -> stringResource(R.string.care_task_status_done)
    CareTaskLogStatus.SKIPPED -> stringResource(R.string.care_task_status_skipped)
    CareTaskLogStatus.IN_PROGRESS -> stringResource(
        R.string.care_task_in_progress_elapsed,
        occurrence.elapsedMinutes ?: 0,
    )
    null -> stringResource(R.string.care_task_status_pending)
}

@Composable
private fun HistoryRow(log: CareTaskLog) {
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm") }
    val label = fmt.format(Instant.ofEpochMilli(log.scheduledTimeMs).atZone(zone))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MedLogSpacing.Large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MedLogSpacing.Medium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(historyStatusText(log), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun historyStatusText(log: CareTaskLog): String {
    val status = when (log.status) {
        CareTaskLogStatus.DONE -> stringResource(R.string.care_task_status_done)
        CareTaskLogStatus.SKIPPED -> stringResource(R.string.care_task_status_skipped)
        CareTaskLogStatus.IN_PROGRESS -> stringResource(R.string.care_task_status_in_progress)
    }
    val minutes = log.actualDurationMinutes
    return if (minutes != null) "$status · ${stringResource(R.string.care_task_duration_value, minutes)}" else status
}

// ─── T8 过程数据入口 ────────────────────────────────────────────────────────

/** 照护事项可记录的过程数据指标；体位是分类值，走 CareTaskLog，不在此列。 */
private val careTaskMetricOptions = listOf(
    HealthType.SPO2,
    HealthType.OXYGEN_FLOW,
    HealthType.READING_COUNT,
)

private const val TAG_MEASUREMENT_VALUE = "care_task_measurement_value"
private const val TAG_MEASUREMENT_SECONDARY = "care_task_measurement_secondary"
private const val TAG_MEASUREMENT_NOTES = "care_task_measurement_notes"
private const val TAG_MEASUREMENT_SAVE = "care_task_measurement_save"
private const val TAG_POSTURE_INPUT = "care_task_posture_input"
private const val TAG_COMPLETION_NOTES = "care_task_completion_notes"
private const val TAG_COMPLETE_CONFIRM = "care_task_complete_confirm"

/** 「记录一次」：选指标 + 数值（+ 可选次值/备注），确认后交给 UseCase 写入健康表。 */
@Composable
private fun RecordMeasurementDialog(
    onDismiss: () -> Unit,
    onConfirm: (type: HealthType, value: Double, secondaryValue: Double?, notes: String) -> Unit,
) {
    var selected by remember { mutableStateOf(careTaskMetricOptions.first()) }
    var valueText by remember { mutableStateOf("") }
    var secondaryText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.care_task_record_measurement)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                Text(
                    stringResource(R.string.care_task_record_metric_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                    careTaskMetricOptions.forEach { type ->
                        // 先取 label 再放进 Chip：stringResource 不能在非 composable lambda 里调用。
                        val label = stringResource(type.labelRes)
                        FilterChip(
                            selected = type == selected,
                            onClick = { selected = type },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(stringResource(R.string.care_task_record_value_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_MEASUREMENT_VALUE),
                )
                OutlinedTextField(
                    value = secondaryText,
                    onValueChange = { secondaryText = it },
                    label = { Text(stringResource(R.string.care_task_record_secondary_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_MEASUREMENT_SECONDARY),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.care_task_record_notes_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_MEASUREMENT_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = valueText.trim().toDoubleOrNull() ?: return@TextButton
                    onConfirm(selected, value, secondaryText.trim().toDoubleOrNull(), notes.trim())
                },
                modifier = Modifier.testTag(TAG_MEASUREMENT_SAVE),
            ) {
                Text(stringResource(R.string.care_task_record_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 完成本次：可选的体位（左/右/平卧，落 CareTaskLog）与本次备注。 */
@Composable
private fun CompleteTaskDialog(onDismiss: () -> Unit, onConfirm: (postureNote: String?, notes: String) -> Unit) {
    var posture by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.care_task_complete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                OutlinedTextField(
                    value = posture,
                    onValueChange = { posture = it },
                    label = { Text(stringResource(R.string.care_task_posture_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_POSTURE_INPUT),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.care_task_completion_notes_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_COMPLETION_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(posture.trim().ifEmpty { null }, notes.trim()) },
                modifier = Modifier.testTag(TAG_COMPLETE_CONFIRM),
            ) {
                Text(stringResource(R.string.care_task_action_complete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
