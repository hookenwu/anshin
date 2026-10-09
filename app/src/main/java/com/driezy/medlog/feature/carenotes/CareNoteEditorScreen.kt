package com.driezy.medlog.feature.carenotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.repository.CareNoteTarget
import com.driezy.medlog.feature.medications.editor.DatePickerField
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.components.TopBarAction
import com.driezy.medlog.ui.components.TopBarActionPriority
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 照护笔记编辑器 Route：新建与编辑共用。
 *
 * @param noteId 非空为编辑模式（进入即加载），null 为新建。
 * @param prelinkType 上下文快捷新增预挂关联的目标类型（null 代表无预挂）。
 * @param prelinkId 预挂关联的目标 id（null 代表无预挂）。
 */
@Composable
fun CareNoteEditorScreen(
    noteId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    prelinkType: String? = null,
    prelinkId: Long? = null,
    viewModel: CareNoteEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_note_editor_failed)

    LaunchedEffect(noteId) {
        if (noteId != null) viewModel.onAction(CareNoteEditorUiAction.LoadExisting(noteId))
    }
    // 上下文快捷新增：预挂关联（含成员校验；不一致时不预挂并给出中性提示）。
    LaunchedEffect(prelinkType, prelinkId) {
        if (prelinkType != null && prelinkId != null) {
            viewModel.onAction(CareNoteEditorUiAction.PreloadQuickAddLink(prelinkType, prelinkId))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareNoteEditorUiEffect.Saved, CareNoteEditorUiEffect.NavigateBack -> onSaved()
                is CareNoteEditorUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
            }
        }
    }

    CareNoteEditorContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CareNoteEditorContent(
    uiState: CareNoteEditorUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (CareNoteEditorUiAction) -> Unit,
) {
    val draft = uiState.draft
    val saveLabel = stringResource(R.string.care_note_save)
    var overlay by remember { mutableStateOf<ScreenOverlay?>(null) }
    var quickAddOpen by remember { mutableStateOf(false) }
    val deleteTitle = stringResource(R.string.care_note_delete_title)
    val deleteBody = stringResource(R.string.care_note_delete_body)
    val deleteConfirm = stringResource(R.string.care_note_delete)
    val cancelLabel = stringResource(R.string.cancel)

    MedLogScreenScaffold(
        title = {
            Text(
                stringResource(
                    if (uiState.isEditing) R.string.care_note_edit_title else R.string.care_note_new_title,
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
        snackbarHostState = snackbarHostState,
        onChromeAction = { if (it == "save") onAction(CareNoteEditorUiAction.Save) },
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
                onValueChange = { onAction(CareNoteEditorUiAction.TitleChanged(it)) },
                label = { Text(stringResource(R.string.care_note_field_title)) },
                singleLine = false,
                isError = uiState.validationError == CareNoteValidationError.EMPTY_TITLE,
                modifier = Modifier.fillMaxWidth().testTag("careNoteTitleField"),
            )
            OutlinedTextField(
                value = draft.body,
                onValueChange = { onAction(CareNoteEditorUiAction.BodyChanged(it)) },
                label = { Text(stringResource(R.string.care_note_field_body)) },
                singleLine = false,
                minLines = 3,
                isError = uiState.validationError == CareNoteValidationError.EMPTY_BODY,
                modifier = Modifier.fillMaxWidth().testTag("careNoteBodyField"),
            )
            when (uiState.validationError) {
                CareNoteValidationError.EMPTY_TITLE -> Text(
                    stringResource(R.string.care_note_error_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                CareNoteValidationError.EMPTY_BODY -> Text(
                    stringResource(R.string.care_note_error_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            // 强引导：不强制显式选择归属，但提示怎么写；类型恒可见。
            Text(
                text = stringResource(R.string.care_note_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("careNoteGuidance"),
            )

            // 上下文快捷新增被拒时的中性提示（目标属于其他成员，绝不预挂跨成员关联）。
            if (uiState.quickAddRefused) {
                Text(
                    text = stringResource(R.string.care_note_quick_add_refused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("careNoteQuickAddRefused"),
                )
            }

            FieldLabel(stringResource(R.string.care_note_field_attribution_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                CareNoteAttributionType.all.forEach { type ->
                    FilterChip(
                        selected = draft.attributionType == type,
                        onClick = { onAction(CareNoteEditorUiAction.AttributionTypeChanged(type)) },
                        label = { Text(attributionTypeLabel(type)) },
                        modifier = Modifier.testTag("careNoteType:$type"),
                    )
                }
            }

            OutlinedTextField(
                value = draft.attributionName,
                onValueChange = { onAction(CareNoteEditorUiAction.AttributionNameChanged(it)) },
                label = { Text(stringResource(R.string.care_note_field_attribution_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("careNoteAttributionNameField"),
            )
            // 选择器：输入即搜索当前成员的已有人员，点选填写（前缀/包含皆可）。
            // 选中人员**不改动、不锁定归属类型**——类型只属于这条笔记（docs/care-people.md §3）。
            if (uiState.personOptions.isNotEmpty()) {
                FieldLabel(stringResource(R.string.care_note_person_suggestions))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                    uiState.personOptions.forEach { person ->
                        FilterChip(
                            selected = draft.attributionPersonId == person.id,
                            onClick = {
                                onAction(CareNoteEditorUiAction.AttributionPersonSelected(person.id, person.name))
                            },
                            label = { Text(person.name) },
                            modifier = Modifier.testTag("careNotePerson:${person.id}"),
                        )
                    }
                }
            }
            // 快速新增：只填姓名；保存后自动选中并留在编辑器（不离开记录流程）。
            TextButton(
                onClick = { quickAddOpen = true },
                modifier = Modifier.testTag("careNotePersonQuickAdd"),
            ) {
                Text(stringResource(R.string.care_note_person_add))
            }
            DatePickerField(
                label = stringResource(R.string.care_note_field_attribution_at),
                timestamp = draft.attributionAtMs,
                onPick = { onAction(CareNoteEditorUiAction.AttributionAtChanged(it)) },
                nullable = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.attributionText,
                onValueChange = { onAction(CareNoteEditorUiAction.AttributionTextChanged(it)) },
                label = { Text(stringResource(R.string.care_note_field_attribution_text)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().testTag("careNoteAttributionTextField"),
            )

            FieldLabel(stringResource(R.string.care_note_field_links))
            if (uiState.linkOptions.isEmpty()) {
                Text(
                    stringResource(R.string.care_note_links_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                    uiState.linkOptions.forEach { option ->
                        val target = CareNoteTarget(option.targetType, option.targetId)
                        FilterChip(
                            selected = target in draft.links,
                            onClick = { onAction(CareNoteEditorUiAction.ToggleLink(target)) },
                            label = { Text(linkOptionLabel(option)) },
                            modifier = Modifier.testTag("careNoteLink:${option.targetType}:${option.targetId}"),
                        )
                    }
                }
            }

            FieldLabel(stringResource(R.string.care_note_field_status))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                CareNoteStatus.all.forEach { status ->
                    FilterChip(
                        selected = draft.status == status,
                        onClick = { onAction(CareNoteEditorUiAction.StatusChanged(status)) },
                        label = { Text(statusLabel(status)) },
                        modifier = Modifier.testTag("careNoteStatus:$status"),
                    )
                }
            }
            if (draft.status == CareNoteStatus.SUPERSEDED) {
                OutlinedTextField(
                    value = draft.supersededText,
                    onValueChange = { onAction(CareNoteEditorUiAction.SupersededTextChanged(it)) },
                    label = { Text(stringResource(R.string.care_note_superseded_dialog_hint)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().testTag("careNoteSupersededTextField"),
                )
            }

            // 删除仅对已存在的笔记开放；破坏性操作走既有确认惯用法，确认后离开编辑器。
            if (uiState.isEditing) {
                TextButton(
                    onClick = {
                        overlay = ScreenOverlay.Confirm(
                            id = "careNoteEditor:delete",
                            title = deleteTitle,
                            body = deleteBody,
                            confirmLabel = deleteConfirm,
                            dismissLabel = cancelLabel,
                            isDanger = true,
                        )
                    },
                    modifier = Modifier.testTag("careNoteEditorDelete"),
                ) {
                    Text(stringResource(R.string.care_note_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    ScreenOverlayHost(
        overlay = overlay,
        onDismiss = { overlay = null },
        onConfirm = { _, _ -> onAction(CareNoteEditorUiAction.Delete) },
    )

    if (quickAddOpen) {
        QuickAddPersonDialog(
            onDismiss = { quickAddOpen = false },
            onConfirm = { name ->
                onAction(CareNoteEditorUiAction.QuickAddPerson(name))
                quickAddOpen = false
            },
        )
    }
}

/**「＋ 新增人员」：只要求姓名（其余字段可在人员档案里补），确认后自动选中并留在编辑器。 */
@Composable
private fun QuickAddPersonDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.care_note_person_add_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.care_note_person_add_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("careNotePersonQuickAddField"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("careNotePersonQuickAddConfirm"),
            ) {
                Text(stringResource(R.string.care_note_person_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    CareNoteStatus.QUESTIONABLE -> stringResource(R.string.care_note_status_questionable)
    CareNoteStatus.SUPERSEDED -> stringResource(R.string.care_note_status_superseded)
    else -> stringResource(R.string.care_note_status_active)
}

@Composable
private fun linkOptionLabel(option: CareNoteLinkOption): String {
    if (option.label.isBlank()) return stringResource(R.string.care_note_link_unknown, option.targetId)
    val prefix = when (option.targetType) {
        CareNoteTargetType.MEDICATION -> stringResource(R.string.care_note_link_medication)
        CareNoteTargetType.CARE_TASK -> stringResource(R.string.care_note_link_care_task)
        else -> stringResource(R.string.care_note_link_todo)
    }
    return "$prefix · ${option.label}"
}
