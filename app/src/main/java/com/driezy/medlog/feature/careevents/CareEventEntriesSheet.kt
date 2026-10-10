package com.driezy.medlog.feature.careevents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareEventLog
import com.driezy.medlog.feature.medications.editor.DatePickerField
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 排便记录列表（补记 / 就地编辑 / 物理删除）。
 *
 * **不加底部 Tab、不新增区块**：由「今日计划」标题行的状态行打开（docs/tracked-events-spec.md §6）。
 * 所有写入都经传入的回调（= `HomeUiAction`）→ `CareEventRepository` 的单一命令入口，
 * 因此派生状态重算、**绝不即时通知**的语义被继承而非重实现（R11）。
 */
@Composable
internal fun CareEventEntriesSheet(
    entries: List<CareEventLog>,
    savingIds: Set<Long>,
    onRecordAt: (occurredAtMs: Long, note: String?) -> Unit,
    onEdit: (id: Long, occurredAtMs: Long, note: String?) -> Unit,
    onDelete: (id: Long) -> Unit,
    onClose: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm") }
    var editor by remember { mutableStateOf<CareEventDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<CareEventLog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(MedLogSpacing.Large)
            .testTag("homeCareEventEntries"),
        verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_care_event_entries_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose, modifier = Modifier.testTag("homeCareEventEntriesClose")) {
                Text(stringResource(R.string.home_close))
            }
        }
        TextButton(
            onClick = { editor = CareEventDraft(id = null, occurredAtMs = System.currentTimeMillis(), note = "") },
            modifier = Modifier.testTag("homeCareEventBackdate"),
        ) {
            MedLogIcon(MedLogIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(MedLogSpacing.Tiny))
            Text(stringResource(R.string.home_care_event_backdate))
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.home_care_event_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("homeCareEventEntriesEmpty"),
            )
        } else {
            HorizontalDivider()
            entries.forEach { entry ->
                CareEventEntryRow(
                    entry = entry,
                    zone = zone,
                    formatter = formatter,
                    enabled = entry.id !in savingIds,
                    onEdit = { editor = CareEventDraft(entry.id, entry.occurredAtMs, entry.note.orEmpty()) },
                    onDelete = { pendingDelete = entry },
                )
                HorizontalDivider()
            }
        }
    }

    editor?.let { draft ->
        CareEventMomentDialog(
            draft = draft,
            zone = zone,
            onConfirm = { ms, note ->
                if (draft.id == null) onRecordAt(ms, note) else onEdit(draft.id, ms, note)
                editor = null
            },
            onDismiss = { editor = null },
        )
    }

    // 删除复用仓库既有的destructive 确认惯例（ScreenOverlay.Confirm + isDanger）。
    ScreenOverlayHost(
        overlay = pendingDelete?.let { entry ->
            ScreenOverlay.Confirm(
                id = "home:careEvent:delete:${entry.id}",
                title = stringResource(R.string.home_care_event_delete_title),
                body = stringResource(R.string.home_care_event_delete_body),
                confirmLabel = stringResource(R.string.home_care_event_delete),
                dismissLabel = stringResource(R.string.cancel),
                targetKey = entry.id.toString(),
                isDanger = true,
            )
        },
        onDismiss = { pendingDelete = null },
        onConfirm = { _, _ -> pendingDelete?.let { onDelete(it.id) } },
    )
}

@Composable
private fun CareEventEntryRow(
    entry: CareEventLog,
    zone: ZoneId,
    formatter: DateTimeFormatter,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("homeCareEventEntry:${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatter.format(Instant.ofEpochMilli(entry.occurredAtMs).atZone(zone)),
                style = MaterialTheme.typography.bodyLarge,
            )
            entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.testTag("homeCareEventEdit:${entry.id}"),
        ) {
            Text(stringResource(R.string.home_care_event_edit))
        }
        TextButton(
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.testTag("homeCareEventDelete:${entry.id}"),
        ) {
            Text(stringResource(R.string.home_care_event_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class CareEventDraft(val id: Long?, val occurredAtMs: Long, val note: String)

/**
 * 记录时刻编辑器：新建补记（[CareEventDraft.id] == null）与就地编辑共用。
 * 日期经既有 [DatePickerField]，时刻经 M3 [TimeInput]，合并为设备本地时区的 epoch 毫秒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareEventMomentDialog(
    draft: CareEventDraft,
    zone: ZoneId,
    onConfirm: (occurredAtMs: Long, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(draft) { Instant.ofEpochMilli(draft.occurredAtMs).atZone(zone) }
    var dateMs by remember(draft) { mutableStateOf(draft.occurredAtMs) }
    var note by remember(draft) { mutableStateOf(draft.note) }
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_care_event_moment)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium)) {
                DatePickerField(
                    label = stringResource(R.string.home_care_event_moment),
                    timestamp = dateMs,
                    onPick = { picked -> if (picked != null) dateMs = picked },
                    zone = zone,
                )
                TimeInput(state = timeState)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.home_care_event_note_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val localDate = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
                    val ms = localDate.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli()
                    onConfirm(ms, note.trim().ifEmpty { null })
                },
                modifier = Modifier.testTag("homeCareEventSave"),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
