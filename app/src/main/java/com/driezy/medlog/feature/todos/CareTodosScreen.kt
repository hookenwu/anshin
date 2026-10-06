package com.driezy.medlog.feature.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.RefreshWhileVisible
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenEmptyState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待办列表页 Route：进行中 / 历史两个 tab；历史里可撤销（重开）。
 * 入口在首页「更多」溢出菜单（照护事项同款），**不是底部 tab**。
 */
@Composable
fun CareTodosScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: CareTodosViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_todo_load_failed)

    RefreshWhileVisible { viewModel.onAction(CareTodosUiAction.Refresh) }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CareTodosUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
            }
        }
    }

    CareTodosContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAdd = onAdd,
        onOpen = onOpen,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun CareTodosContent(
    state: CareTodosUiState,
    snackbarHostState: SnackbarHostState,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onAction: (CareTodosUiAction) -> Unit,
) {
    val addLabel = stringResource(R.string.care_todo_fab_add)
    val emptyState = when {
        state.isLoading || state.visibleTodos.isNotEmpty() -> null
        state.selectedTab == CareTodosTab.HISTORY -> ScreenEmptyState(
            title = stringResource(R.string.care_todo_history_empty_title),
            body = stringResource(R.string.care_todo_history_empty_body),
            icon = MedLogIcons.History,
        )
        else -> ScreenEmptyState(
            title = stringResource(R.string.care_todo_active_empty_title),
            body = stringResource(R.string.care_todo_active_empty_body),
            icon = MedLogIcons.DoneAll,
            actionLabel = addLabel,
            actionId = "add",
        )
    }

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.care_todos_title)) },
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
                    selected = state.selectedTab == CareTodosTab.ACTIVE,
                    onClick = { onAction(CareTodosUiAction.SetTab(CareTodosTab.ACTIVE)) },
                    label = { Text(stringResource(R.string.care_todo_tab_active)) },
                    modifier = Modifier.testTag("todoTabActive"),
                )
                FilterChip(
                    selected = state.selectedTab == CareTodosTab.HISTORY,
                    onClick = { onAction(CareTodosUiAction.SetTab(CareTodosTab.HISTORY)) },
                    label = { Text(stringResource(R.string.care_todo_tab_history)) },
                    modifier = Modifier.testTag("todoTabHistory"),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = MedLogSpacing.ScreenContentWithFab,
                verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
            ) {
                items(state.visibleTodos, key = { it.id }) { todo ->
                    CareTodoCard(
                        todo = todo,
                        showHistory = state.selectedTab == CareTodosTab.HISTORY,
                        saving = todo.id in state.savingIds,
                        onOpen = onOpen,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CareTodoCard(
    todo: CareTodo,
    showHistory: Boolean,
    saving: Boolean,
    onOpen: (Long) -> Unit,
    onAction: (CareTodosUiAction) -> Unit,
) {
    Card(
        onClick = { if (!showHistory) onOpen(todo.id) },
        enabled = !showHistory,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MedLogSpacing.Large)
            .testTag("todoRow:${todo.id}"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(MedLogSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            Text(todo.title, style = MaterialTheme.typography.titleMedium)
            val subtitle = if (showHistory) {
                stringResource(
                    if (todo.status == CareTodoStatus.CANCELLED) {
                        R.string.care_todo_status_cancelled
                    } else {
                        R.string.care_todo_status_done
                    },
                )
            } else {
                todo.dueAtMs?.let { "截止 ${formatDueDate(it)}" }
            }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showHistory) {
                    TodoActionButton(
                        labelRes = R.string.care_todo_action_reopen,
                        tag = "todoReopen:${todo.id}",
                        enabled = !saving,
                        onClick = { onAction(CareTodosUiAction.Reopen(todo.id)) },
                    )
                } else {
                    TodoActionButton(
                        labelRes = R.string.care_todo_action_complete,
                        tag = "todoComplete:${todo.id}",
                        enabled = !saving,
                        onClick = { onAction(CareTodosUiAction.Complete(todo.id)) },
                    )
                    TodoActionButton(
                        labelRes = R.string.care_todo_action_cancel,
                        tag = "todoCancel:${todo.id}",
                        enabled = !saving,
                        onClick = { onAction(CareTodosUiAction.Cancel(todo.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoActionButton(labelRes: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(36.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = MedLogSpacing.Medium),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    }
}

private val TODO_DUE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

private fun formatDueDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TODO_DUE_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())
