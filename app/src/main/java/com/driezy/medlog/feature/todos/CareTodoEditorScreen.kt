package com.driezy.medlog.feature.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.feature.carenotes.CareNoteQuickAddButton
import com.driezy.medlog.feature.carenotes.RelatedNotesSection
import com.driezy.medlog.feature.medications.editor.DatePickerField
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.TopBarAction
import com.driezy.medlog.ui.components.TopBarActionPriority
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 待办编辑器 Route：新建与编辑共用。
 *
 * @param todoId 非空为编辑模式（进入即加载），null 为新建。
 */
@Composable
fun CareTodoEditorScreen(
    todoId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onQuickAddNote: () -> Unit,
    viewModel: CareTodoEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(todoId) {
        if (todoId != null) viewModel.onAction(CareTodoEditorUiAction.LoadExisting(todoId))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareTodoEditorUiEffect.Saved -> onSaved()
                is CareTodoEditorUiEffect.Failed -> Unit
            }
        }
    }

    CareTodoEditorContent(
        uiState = uiState,
        onBack = onBack,
        onAction = viewModel::onAction,
        onQuickAddNote = onQuickAddNote,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareTodoEditorContent(
    uiState: CareTodoEditorUiState,
    onBack: () -> Unit,
    onAction: (CareTodoEditorUiAction) -> Unit,
    onQuickAddNote: () -> Unit = {},
) {
    val draft = uiState.draft
    val saveLabel = stringResource(R.string.care_todo_save)

    MedLogScreenScaffold(
        title = {
            Text(
                stringResource(
                    if (uiState.isEditing) R.string.care_todo_edit_title else R.string.care_todo_new_title,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                MedLogIcon(MedLogIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = listOf(
            TopBarAction(
                id = "save",
                label = saveLabel,
                icon = MedLogIcons.Check,
                priority = TopBarActionPriority.Primary,
                enabled = !uiState.isSaving && !uiState.isLoading,
            ),
        ),
        chromeState = ScreenChromeState(isLoading = uiState.isLoading),
        onChromeAction = { if (it == "save") onAction(CareTodoEditorUiAction.Save) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onAction(CareTodoEditorUiAction.TitleChanged(it)) },
                label = { Text(stringResource(R.string.care_todo_field_title)) },
                singleLine = false,
                isError = uiState.validationError == CareTodoValidationError.EMPTY_TITLE,
                modifier = Modifier.fillMaxWidth().testTag("todoTitleField"),
            )
            val validation = uiState.validationError
            if (validation != null) {
                Text(
                    stringResource(R.string.care_todo_error_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            DatePickerField(
                label = stringResource(R.string.care_todo_field_due),
                timestamp = draft.dueAtMs,
                onPick = { onAction(CareTodoEditorUiAction.DueChanged(it)) },
                nullable = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.sourceNote,
                onValueChange = { onAction(CareTodoEditorUiAction.SourceNoteChanged(it)) },
                label = { Text(stringResource(R.string.care_todo_field_source_note)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().testTag("todoSourceNoteField"),
            )

            val sourceType = draft.sourceType
            val sourceId = draft.sourceId
            if (sourceType != null || sourceId != null) {
                val label = buildString {
                    append(sourceType.orEmpty())
                    sourceId?.let { append(" #$it") }
                }.trim()
                Text(
                    stringResource(R.string.care_todo_field_source_meta, label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("todoSourceMeta"),
                )
            }

            // ── 相关笔记（编辑既有待办时；空态不渲染卡片）+ 独立文本快捷新增（含成员校验）──
            if (uiState.isEditing) {
                RelatedNotesSection(notes = uiState.relatedNotes, contentPadding = 0.dp)
                CareNoteQuickAddButton(onClick = onQuickAddNote, contentPadding = 0.dp)
            }
        }
    }
}
