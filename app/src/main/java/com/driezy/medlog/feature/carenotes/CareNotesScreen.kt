package com.driezy.medlog.feature.carenotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.RefreshWhileVisible
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenEmptyState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 照护笔记列表页 Route：搜索 + 状态过滤；默认列表 `ACTIVE` + `QUESTIONABLE`，
 * 主动搜索仍返回 `SUPERSEDED`（标记「已被更新」）。入口在首页「更多」溢出菜单，**不是底部 tab**。
 */
@Composable
fun CareNotesScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: CareNotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_note_load_failed)

    RefreshWhileVisible { viewModel.onAction(CareNotesUiAction.Refresh) }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CareNotesUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
            }
        }
    }

    CareNotesContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAdd = onAdd,
        onOpen = onOpen,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareNotesContent(
    state: CareNotesUiState,
    snackbarHostState: SnackbarHostState,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onAction: (CareNotesUiAction) -> Unit,
) {
    var supersedeFor by remember { mutableStateOf<Long?>(null) }
    val addLabel = stringResource(R.string.care_note_fab_add)
    val emptyState = when {
        state.isLoading || state.notes.isNotEmpty() -> null
        state.isSearching -> ScreenEmptyState(
            title = stringResource(R.string.care_note_search_empty_title),
            body = stringResource(R.string.care_note_search_empty_body),
            icon = MedLogIcons.Search,
        )
        else -> ScreenEmptyState(
            title = stringResource(R.string.care_note_list_empty_title),
            body = stringResource(R.string.care_note_list_empty_body),
            icon = MedLogIcons.EditNote,
            actionLabel = addLabel,
            actionId = "add",
        )
    }

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.care_notes_title)) },
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
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(CareNotesUiAction.QueryChanged(it)) },
                placeholder = { Text(stringResource(R.string.care_note_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Small)
                    .testTag("careNoteSearchField"),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = MedLogSpacing.ScreenContentWithFab,
                verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
            ) {
                items(state.notes, key = { it.note.id }) { row ->
                    CareNoteCard(
                        note = row.note,
                        modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                        danglingLinks = row.danglingLinks,
                        onRemoveDanglingLinks = { onAction(CareNotesUiAction.RemoveDanglingLinks(row.note.id)) },
                        trailing = {
                            CareNoteStatusActions(
                                status = row.note.status,
                                onEdit = { onOpen(row.note.id) },
                                onQuestionable = { onAction(CareNotesUiAction.MarkQuestionable(row.note.id)) },
                                onSuperseded = { supersedeFor = row.note.id },
                                onActive = { onAction(CareNotesUiAction.MarkActive(row.note.id)) },
                            )
                        },
                    )
                }
            }
        }
    }

    supersedeFor?.let { noteId ->
        SupersedeDialog(
            onDismiss = { supersedeFor = null },
            onConfirm = { text ->
                onAction(CareNotesUiAction.MarkSuperseded(noteId, text))
                supersedeFor = null
            },
        )
    }
}

/** 行内状态切换：只有用户点击才会改变状态（docs/care-notes.md §3）。 */
@Composable
private fun CareNoteStatusActions(
    status: String,
    onEdit: () -> Unit,
    onQuestionable: () -> Unit,
    onSuperseded: () -> Unit,
    onActive: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
        TextButton(onClick = onEdit) { Text(stringResource(R.string.care_note_action_edit)) }
        if (status != CareNoteStatus.QUESTIONABLE) {
            TextButton(
                onClick = onQuestionable,
                modifier = Modifier.testTag("careNoteMarkQuestionable"),
            ) {
                Text(stringResource(R.string.care_note_action_mark_questionable))
            }
        }
        if (status != CareNoteStatus.SUPERSEDED) {
            TextButton(
                onClick = onSuperseded,
                modifier = Modifier.testTag("careNoteMarkSuperseded"),
            ) {
                Text(stringResource(R.string.care_note_action_mark_superseded))
            }
        } else {
            TextButton(
                onClick = onActive,
                modifier = Modifier.testTag("careNoteMarkActive"),
            ) {
                Text(stringResource(R.string.care_note_action_mark_active))
            }
        }
    }
}

@Composable
private fun SupersedeDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.care_note_superseded_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.care_note_superseded_dialog_hint)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().testTag("careNoteSupersededInput"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifEmpty { null }) },
                modifier = Modifier.testTag("careNoteSupersededConfirm"),
            ) {
                Text(stringResource(R.string.care_note_superseded_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
