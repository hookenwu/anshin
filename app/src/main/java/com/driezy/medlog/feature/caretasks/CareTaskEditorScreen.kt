package com.driezy.medlog.feature.caretasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.TimePeriod
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
import com.driezy.medlog.ui.util.labelRes
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * 照护事项编辑器 Route：新建与编辑共用。
 *
 * @param careTaskId 非空为编辑模式（进入即加载），null 为新建。
 */
@Composable
fun CareTaskEditorScreen(
    careTaskId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CareTaskEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(careTaskId) {
        if (careTaskId != null) viewModel.onAction(CareTaskEditorUiAction.LoadExisting(careTaskId))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareTaskEditorUiEffect.Saved, CareTaskEditorUiEffect.NavigateBack -> onSaved()
                is CareTaskEditorUiEffect.Failed -> Unit
            }
        }
    }

    CareTaskEditorContent(uiState = uiState, onBack = onBack, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareTaskEditorContent(
    uiState: CareTaskEditorUiState,
    onBack: () -> Unit,
    onAction: (CareTaskEditorUiAction) -> Unit,
) {
    var overlay by remember { mutableStateOf<ScreenOverlay?>(null) }
    val draft = uiState.draft
    val saveLabel = stringResource(R.string.care_task_save)
    val deleteTitle = stringResource(R.string.care_task_delete_title)
    val deleteBody = stringResource(R.string.care_task_delete_body)
    val deleteConfirm = stringResource(R.string.care_task_delete)
    val cancelLabel = stringResource(R.string.cancel)

    MedLogScreenScaffold(
        title = {
            Text(
                stringResource(
                    if (uiState.isEditing) R.string.care_task_edit_title else R.string.care_task_new_title,
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
        onChromeAction = { if (it == "save") onAction(CareTaskEditorUiAction.Save) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = MedLogSpacing.Large)
                .padding(bottom = MedLogSpacing.XXLarge),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
        ) {
            uiState.validationError?.let { error ->
                Text(
                    stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onAction(CareTaskEditorUiAction.TitleChanged(it)) },
                label = { Text(stringResource(R.string.care_task_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EditorSection(stringResource(R.string.care_task_field_category)) {
                ChipRow {
                    CareTaskCategory.all.forEach { category ->
                        FilterChip(
                            selected = draft.category == category,
                            onClick = { onAction(CareTaskEditorUiAction.CategoryChanged(category)) },
                            label = { Text(careTaskCategoryLabel(category)) },
                        )
                    }
                }
            }

            EditorSection(stringResource(R.string.care_task_field_completion)) {
                ChipRow {
                    FilterChip(
                        selected = draft.completionMode == CareTaskCompletionMode.TOGGLE,
                        onClick = {
                            onAction(CareTaskEditorUiAction.CompletionModeChanged(CareTaskCompletionMode.TOGGLE))
                        },
                        label = { Text(stringResource(R.string.care_task_mode_toggle)) },
                    )
                    FilterChip(
                        selected = draft.completionMode == CareTaskCompletionMode.DURATION,
                        onClick = {
                            onAction(CareTaskEditorUiAction.CompletionModeChanged(CareTaskCompletionMode.DURATION))
                        },
                        label = { Text(stringResource(R.string.care_task_mode_duration)) },
                    )
                }
                if (draft.completionMode == CareTaskCompletionMode.DURATION) {
                    NumberField(
                        label = stringResource(R.string.care_task_default_minutes),
                        value = draft.defaultDurationMinutes,
                        onValueChange = { onAction(CareTaskEditorUiAction.DefaultMinutesChanged(it)) },
                    )
                }
            }

            EditorSection(stringResource(R.string.care_task_field_schedule)) {
                ChipRow {
                    CareTaskScheduleKind.entries.forEach { kind ->
                        FilterChip(
                            selected = draft.scheduleKind == kind,
                            onClick = { onAction(CareTaskEditorUiAction.ScheduleKindChanged(kind)) },
                            label = { Text(stringResource(kind.labelRes())) },
                        )
                    }
                }
                when (draft.scheduleKind) {
                    CareTaskScheduleKind.FIXED_TIMES -> {
                        Text(
                            stringResource(R.string.care_task_field_periods),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChipRow {
                            TimePeriod.entries.filter { it != TimePeriod.EXACT }.forEach { period ->
                                FilterChip(
                                    selected = period in draft.timePeriods,
                                    onClick = { onAction(CareTaskEditorUiAction.ToggleTimePeriod(period)) },
                                    label = { Text(stringResource(period.labelRes)) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.care_task_field_times),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CareTaskTimesRow(
                            times = draft.reminderTimes,
                            onAdd = { onAction(CareTaskEditorUiAction.AddTime(it)) },
                            onRemove = { onAction(CareTaskEditorUiAction.RemoveTime(it)) },
                        )
                    }
                    CareTaskScheduleKind.INTERVAL -> NumberField(
                        label = stringResource(R.string.care_task_field_interval_hours),
                        value = draft.intervalHours,
                        onValueChange = { onAction(CareTaskEditorUiAction.IntervalHoursChanged(it)) },
                    )
                    CareTaskScheduleKind.AS_NEEDED -> Unit
                }
            }

            EditorSection(stringResource(R.string.care_task_field_frequency)) {
                ChipRow {
                    FrequencyOption.entries.forEach { option ->
                        FilterChip(
                            selected = draft.frequencyType == option.type,
                            onClick = { onAction(CareTaskEditorUiAction.FrequencyChanged(option.type)) },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
                when (draft.frequencyType) {
                    CareTaskDraft.FREQUENCY_INTERVAL -> NumberField(
                        label = stringResource(R.string.care_task_interval_days),
                        value = draft.frequencyInterval,
                        onValueChange = { onAction(CareTaskEditorUiAction.FrequencyIntervalChanged(it)) },
                    )
                    CareTaskDraft.FREQUENCY_SPECIFIC_DAYS -> {
                        val locale = Locale.getDefault()
                        ChipRow {
                            (1..7).forEach { day ->
                                FilterChip(
                                    selected = day in draft.frequencyDays,
                                    onClick = { onAction(CareTaskEditorUiAction.ToggleWeekday(day)) },
                                    label = {
                                        Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale))
                                    },
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }

            EditorSection(stringResource(R.string.care_task_field_start_date)) {
                DatePickerField(
                    label = stringResource(R.string.care_task_field_start_date),
                    timestamp = draft.startDate,
                    onPick = { picked -> picked?.let { onAction(CareTaskEditorUiAction.StartDateChanged(it)) } },
                )
            }

            OutlinedTextField(
                value = draft.notes,
                onValueChange = { onAction(CareTaskEditorUiAction.NotesChanged(it)) },
                label = { Text(stringResource(R.string.care_task_field_notes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.isEditing) {
                ChipRow {
                    TextButton(onClick = { onAction(CareTaskEditorUiAction.Archive) }) {
                        Text(stringResource(R.string.care_task_archive))
                    }
                    TextButton(onClick = {
                        overlay = ScreenOverlay.Confirm(
                            id = "delete",
                            title = deleteTitle,
                            body = deleteBody,
                            confirmLabel = deleteConfirm,
                            dismissLabel = cancelLabel,
                            isDanger = true,
                        )
                    }) {
                        Text(stringResource(R.string.care_task_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    ScreenOverlayHost(
        overlay = overlay,
        onDismiss = { overlay = null },
        onConfirm = { resolved, _ ->
            if (resolved.id == "delete") onAction(CareTaskEditorUiAction.Delete)
        },
    )
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.all(Char::isDigit)) {
                text = input
                input.toIntOrNull()?.let(onValueChange)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareTaskTimesRow(times: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val now = remember { java.time.LocalTime.now() }
    val pickerState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)

    Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
        ChipRow {
            times.forEach { hhmm ->
                InputChip(
                    selected = true,
                    onClick = {},
                    label = { Text(hhmm) },
                    trailingIcon = {
                        IconButton(onClick = { onRemove(hhmm) }, modifier = Modifier.size(18.dp)) {
                            MedLogIcon(MedLogIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    },
                )
            }
            AssistChip(
                onClick = { showPicker = !showPicker },
                label = { Text(stringResource(R.string.care_task_add_time)) },
                leadingIcon = {
                    MedLogIcon(MedLogIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
        if (showPicker) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(MedLogSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
                ) {
                    TimeInput(state = pickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) }
                        Spacer(modifier = Modifier.size(MedLogSpacing.Small))
                        FilledTonalButton(
                            onClick = {
                                onAdd("%02d:%02d".format(pickerState.hour, pickerState.minute))
                                showPicker = false
                            },
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }
}

private fun CareTaskValidationError.messageRes(): Int = when (this) {
    CareTaskValidationError.EMPTY_TITLE -> R.string.care_task_error_title
    CareTaskValidationError.MISSING_TIME -> R.string.care_task_error_time
    CareTaskValidationError.MISSING_INTERVAL -> R.string.care_task_error_interval
}

private fun CareTaskScheduleKind.labelRes(): Int = when (this) {
    CareTaskScheduleKind.FIXED_TIMES -> R.string.care_task_schedule_fixed
    CareTaskScheduleKind.INTERVAL -> R.string.care_task_schedule_interval
    CareTaskScheduleKind.AS_NEEDED -> R.string.care_task_schedule_as_needed
}

private enum class FrequencyOption(val type: String, val labelRes: Int) {
    DAILY(CareTaskDraft.FREQUENCY_DAILY, R.string.care_task_freq_daily),
    INTERVAL(CareTaskDraft.FREQUENCY_INTERVAL, R.string.care_task_freq_interval),
    SPECIFIC_DAYS(CareTaskDraft.FREQUENCY_SPECIFIC_DAYS, R.string.care_task_freq_specific_days),
}
