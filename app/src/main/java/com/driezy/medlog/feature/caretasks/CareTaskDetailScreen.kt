package com.driezy.medlog.feature.caretasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
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

    RefreshWhileVisible { viewModel.onAction(CareTaskDetailUiAction.RefreshTime) }
    LaunchedEffect(careTaskId) { viewModel.onAction(CareTaskDetailUiAction.Load(careTaskId)) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareTaskDetailUiEffect.NavigateBack -> onBack()
                is CareTaskDetailUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
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
        }
    }

    ScreenOverlayHost(
        overlay = overlay,
        onDismiss = { overlay = null },
        onConfirm = { resolved, _ ->
            if (resolved.id == "delete") onAction(CareTaskDetailUiAction.Delete)
        },
    )
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
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Complete(scheduledMs)) }) {
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
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Complete(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_complete))
                        }
                        TextButton(onClick = { onAction(CareTaskDetailUiAction.Skip(scheduledMs)) }) {
                            Text(stringResource(R.string.care_task_action_skip))
                        }
                    }
                }
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
