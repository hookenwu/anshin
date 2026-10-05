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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.RefreshWhileVisible
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenEmptyState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 照护事项列表页 Route：收集 VM 状态并把命令交给 [CareTasksViewModel]。
 */
@Composable
fun CareTasksScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: CareTasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_task_load_failed)

    RefreshWhileVisible { viewModel.onAction(CareTasksUiAction.Refresh) }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CareTasksUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
            }
        }
    }

    CareTasksContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAdd = onAdd,
        onOpen = onOpen,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun CareTasksContent(
    state: CareTasksUiState,
    snackbarHostState: SnackbarHostState,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onAction: (CareTasksUiAction) -> Unit,
) {
    val addLabel = stringResource(R.string.care_task_fab_add)
    val emptyState = when {
        !state.isLoading && state.visibleTasks.isEmpty() && state.showArchived -> ScreenEmptyState(
            title = stringResource(R.string.care_task_archived_empty_title),
            body = stringResource(R.string.care_task_archived_empty_body),
            icon = MedLogIcons.Archive,
        )
        !state.isLoading && state.visibleTasks.isEmpty() -> ScreenEmptyState(
            title = stringResource(R.string.care_task_active_empty_title),
            body = stringResource(R.string.care_task_active_empty_body),
            icon = MedLogIcons.Favorite,
            actionLabel = addLabel,
            actionId = "add",
        )
        else -> null
    }

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.care_tasks_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                MedLogIcon(MedLogIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        chromeState = ScreenChromeState(
            isLoading = state.isLoading,
            fab = ScreenFab("add", addLabel, MedLogIcons.Add),
            emptyState = emptyState,
        ),
        snackbarHostState = snackbarHostState,
        onChromeAction = { if (it == "add") onAdd() },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MedLogSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
            ) {
                FilterChip(
                    selected = !state.showArchived,
                    onClick = { onAction(CareTasksUiAction.SetShowArchived(false)) },
                    label = { Text(stringResource(R.string.care_task_active)) },
                )
                FilterChip(
                    selected = state.showArchived,
                    onClick = { onAction(CareTasksUiAction.SetShowArchived(true)) },
                    label = { Text(stringResource(R.string.care_task_archived)) },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = MedLogSpacing.ScreenContentWithFab,
                verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
            ) {
                items(state.visibleTasks, key = { it.id }) { task ->
                    CareTaskCard(
                        task = task,
                        showArchived = state.showArchived,
                        todayStatus = state.todayStatus[task.id],
                        onClick = { onOpen(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CareTaskCard(
    task: CareTask,
    showArchived: Boolean,
    todayStatus: CareTaskTodayStatus?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
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
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${careTaskCategoryLabel(task.category)} · ${careTaskScheduleSummary(task)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            todayStatus?.let { status ->
                Text(
                    careTaskTodayStatusLabel(status.kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(
                    if (showArchived) R.string.care_task_archived_hint else R.string.care_task_manage_hint,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
