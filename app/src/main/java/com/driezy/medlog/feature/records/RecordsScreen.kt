package com.driezy.medlog.feature.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.feature.carenotes.CareNoteCard
import com.driezy.medlog.feature.health.symptom.AddEditDiarySheet
import com.driezy.medlog.feature.health.symptom.SymptomDiaryUiAction
import com.driezy.medlog.feature.health.symptom.SymptomDiaryUiState
import com.driezy.medlog.feature.health.symptom.SymptomDiaryViewModel
import com.driezy.medlog.feature.health.symptom.SymptomLogCard
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import java.time.format.DateTimeFormatter

/**
 * 记录中心（Route.Records，label「记录」）：标题 + 搜索框 + 模式 chips + 事件时间倒序混排列表 + ＋。
 *
 * 只读浏览 + 路由（不写入、不产生第三张表）；身心记录编辑器复用既有的
 * [SymptomDiaryViewModel] + [AddEditDiarySheet]，删除/编辑与现状一致。
 *
 * @param diaryAvailable `enableSymptomDiary`：关闭时隐藏全部身心记录面（模式收敛到照护笔记），
 *   照护笔记因此始终可达（详见 docs/record-center-spec.md §3 D1/D4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    diaryAvailable: Boolean,
    onOpenCareNote: (Long) -> Unit,
    onAddCareNote: () -> Unit,
    viewModel: RecordsViewModel = hiltViewModel(),
    diaryViewModel: SymptomDiaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val diaryState by diaryViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(diaryAvailable) {
        viewModel.onAction(RecordsUiAction.DiaryAvailabilityChanged(diaryAvailable))
    }

    RecordsContent(
        state = state,
        diaryState = diaryState,
        onOpenCareNote = onOpenCareNote,
        onAddCareNote = onAddCareNote,
        onAction = viewModel::onAction,
        onDiaryAction = diaryViewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordsContent(
    state: RecordsUiState,
    diaryState: SymptomDiaryUiState,
    onOpenCareNote: (Long) -> Unit,
    onAddCareNote: () -> Unit,
    onAction: (RecordsUiAction) -> Unit,
    onDiaryAction: (SymptomDiaryUiAction) -> Unit,
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    var typeChoiceOpen by remember { mutableStateOf(false) }
    val addLabel = stringResource(R.string.records_fab_add)

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.records_title)) },
        chromeState = ScreenChromeState(
            isLoading = state.isLoading,
            fab = ScreenFab("add", addLabel, MedLogIcons.Add),
        ),
        onChromeAction = { id ->
            if (id == "add") {
                when (recordsAddTarget(state.mode)) {
                    RecordsAddTarget.TYPE_CHOICE -> typeChoiceOpen = true
                    RecordsAddTarget.DIARY_EDITOR -> onDiaryAction(SymptomDiaryUiAction.StartAdd)
                    RecordsAddTarget.CARE_NOTE_EDITOR -> onAddCareNote()
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.searchEnabled) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onAction(RecordsUiAction.QueryChanged(it)) },
                    placeholder = { Text(stringResource(R.string.records_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Small)
                        .testTag("recordsSearchField"),
                )
            }
            if (state.availableModes.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MedLogSpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                ) {
                    state.availableModes.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { onAction(RecordsUiAction.SetMode(mode)) },
                            label = { Text(recordsModeLabel(mode)) },
                            modifier = Modifier.testTag("recordsMode:$mode"),
                        )
                    }
                }
            }

            if (state.showEmpty) {
                // 空态不渲染任何列表壳：只留引导语与 ＋（＋ 由 scaffold 的 FAB 提供）。
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = MedLogSpacing.Large),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.records_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("recordsEmptyHint"),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = MedLogSpacing.ScreenContentWithFab,
                    verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
                ) {
                    items(state.entries, key = { it.key }) { entry ->
                        when (entry) {
                            is RecordEntry.CareNoteEntry -> CareNoteCard(
                                note = entry.row.note,
                                modifier = Modifier
                                    .padding(horizontal = MedLogSpacing.Large)
                                    .clickable { onOpenCareNote(entry.row.note.id) },
                            )
                            is RecordEntry.DiaryEntry -> SymptomLogCard(
                                log = entry.log,
                                dateFormat = dateFormat,
                                onEdit = { onDiaryAction(SymptomDiaryUiAction.StartEdit(entry.log)) },
                                onDelete = { onDiaryAction(SymptomDiaryUiAction.Delete(entry.log.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 「全部」模式点 ＋：先选类型（2 次点击）。
    if (typeChoiceOpen) {
        AlertDialog(
            onDismissRequest = { typeChoiceOpen = false },
            title = { Text(stringResource(R.string.records_add_choose_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                    TextButton(
                        onClick = {
                            typeChoiceOpen = false
                            onDiaryAction(SymptomDiaryUiAction.StartAdd)
                        },
                        modifier = Modifier.testTag("recordsAddDiary"),
                    ) {
                        Text(stringResource(R.string.records_add_diary))
                    }
                    TextButton(
                        onClick = {
                            typeChoiceOpen = false
                            onAddCareNote()
                        },
                        modifier = Modifier.testTag("recordsAddCareNote"),
                    ) {
                        Text(stringResource(R.string.records_add_care_note))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { typeChoiceOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (diaryState.showDialog) {
        ScreenOverlayHost(
            overlay = ScreenOverlay.Custom(id = "records:diary-editor") {
                AddEditDiarySheet(
                    draft = diaryState.draft,
                    onDismiss = { onDiaryAction(SymptomDiaryUiAction.DismissDialog) },
                    onRatingChange = { onDiaryAction(SymptomDiaryUiAction.RatingChanged(it)) },
                    onToggleSymptom = { onDiaryAction(SymptomDiaryUiAction.ToggleSymptom(it)) },
                    onCustomSymptomChange = { onDiaryAction(SymptomDiaryUiAction.CustomSymptomChanged(it)) },
                    onAddCustomSymptom = { onDiaryAction(SymptomDiaryUiAction.AddCustomSymptom) },
                    onToggleSideEffect = { onDiaryAction(SymptomDiaryUiAction.ToggleSideEffect(it)) },
                    onCustomSideEffectChange = { onDiaryAction(SymptomDiaryUiAction.CustomSideEffectChanged(it)) },
                    onAddCustomSideEffect = { onDiaryAction(SymptomDiaryUiAction.AddCustomSideEffect) },
                    onNoteChange = { onDiaryAction(SymptomDiaryUiAction.NoteChanged(it)) },
                    voiceInput = diaryState.voiceInput,
                    onStartVoiceInput = { onDiaryAction(SymptomDiaryUiAction.StartVoiceInput) },
                    onStopVoiceInput = { onDiaryAction(SymptomDiaryUiAction.StopVoiceInput) },
                    onSave = { onDiaryAction(SymptomDiaryUiAction.Save) },
                )
            },
            onDismiss = { onDiaryAction(SymptomDiaryUiAction.DismissDialog) },
        )
    }
}

@Composable
internal fun recordsModeLabel(mode: RecordsMode): String = when (mode) {
    RecordsMode.ALL -> stringResource(R.string.records_mode_all)
    RecordsMode.DIARY -> stringResource(R.string.records_mode_diary)
    RecordsMode.CARE_NOTES -> stringResource(R.string.records_mode_care_notes)
}
