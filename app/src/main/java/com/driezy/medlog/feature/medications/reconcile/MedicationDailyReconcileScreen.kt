package com.driezy.medlog.feature.medications.reconcile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R

/**
 * 今日用药核对屏（docs/medication-daily-reconcile.md §3）。
 *
 * 列表 → 摘要 → 结果；提交前出变更摘要、全停须二次确认（默认取消）、并发 / 跨零点中止后重建摘要。
 * `internal …Content` 供契约测试渲染（状态对象 + 回调）。
 */
@Composable
fun MedicationDailyReconcileScreen(
    onBack: () -> Unit,
    viewModel: MedicationDailyReconcileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MedicationDailyReconcileContent(
        state = state,
        onToggle = viewModel::onToggle,
        onReasonChange = viewModel::onReasonChange,
        onNext = viewModel::toSummary,
        onConfirm = viewModel::confirm,
        onBack = viewModel::backToEdit,
        onDone = onBack,
    )
}

@Composable
internal fun MedicationDailyReconcileContent(
    state: MedicationReconcileState,
    onToggle: (Long) -> Unit,
    onReasonChange: (String) -> Unit,
    onNext: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.medication_reconcile_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.medication_reconcile_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.memberChanged) {
                Text(
                    stringResource(R.string.medication_reconcile_member_changed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.failed) {
                Text(stringResource(R.string.medication_reconcile_failed), color = MaterialTheme.colorScheme.error)
            }
            when (state.step) {
                ReconcileStep.LIST -> ListStep(state, onToggle, onReasonChange, onNext)
                ReconcileStep.SUMMARY -> SummaryStep(state, onConfirm, onBack)
                ReconcileStep.RESULT -> ResultStep(state, onDone)
            }
        }
    }
}

@Composable
private fun ColumnScope.ListStep(
    state: MedicationReconcileState,
    onToggle: (Long) -> Unit,
    onReasonChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    val checkedLabel = stringResource(R.string.medication_reconcile_state_checked)
    val uncheckedLabel = stringResource(R.string.medication_reconcile_state_unchecked)
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.lines, key = { it.id }) { line ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = line.checked, role = Role.Checkbox, onValueChange = { onToggle(line.id) })
                    .semantics {
                        stateDescription = if (line.checked) checkedLabel else uncheckedLabel
                        contentDescription = line.name
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = line.checked, onCheckedChange = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.name, style = MaterialTheme.typography.titleMedium)
                        lineBadge(line)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = state.reason,
        onValueChange = onReasonChange,
        label = { Text(stringResource(R.string.medication_reconcile_reason)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onNext, enabled = state.lines.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.medication_reconcile_step_review))
    }
}

@Composable
private fun SummaryStep(state: MedicationReconcileState, onConfirm: () -> Unit, onBack: () -> Unit) {
    val summary = state.summary ?: return
    var showGuard by remember { mutableStateOf(false) }
    if (state.stale) {
        Text(
            stringResource(R.string.medication_reconcile_stale_summary_rebuilt),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Text(stringResource(R.string.medication_reconcile_summary_title), style = MaterialTheme.typography.titleLarge)
    if (summary.toPause.isEmpty() && summary.toResume.isEmpty()) {
        Text(stringResource(R.string.medication_reconcile_summary_no_changes, summary.unchangedCount))
    } else {
        Text(
            stringResource(
                R.string.medication_reconcile_summary_changes,
                summary.toPause.size,
                pauseNames(state).joinToString("、"),
                summary.toResume.size,
                resumeNames(state).joinToString("、"),
                summary.unchangedCount,
            ),
        )
    }
    Text(stringResource(R.string.medication_reconcile_summary_effective_day, summary.effectiveDay.toString()))
    if (summary.needsSecondConfirmation) {
        val allActivePaused = summary.toPause.isNotEmpty() &&
            summary.toPause.size >= state.lines.count { it.currentlyActive }
        Text(
            if (allActivePaused) {
                stringResource(R.string.medication_reconcile_summary_all_paused_warning, summary.toPause.size)
            } else {
                stringResource(
                    R.string.medication_reconcile_bulk_change_warning,
                    summary.toPause.size + summary.toResume.size,
                )
            },
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.medication_reconcile_back)) }
        Button(onClick = { if (summary.needsSecondConfirmation) showGuard = true else onConfirm() }) {
            Text(stringResource(R.string.medication_reconcile_confirm))
        }
    }
    if (showGuard) {
        val allActivePaused = summary.toPause.isNotEmpty() &&
            summary.toPause.size >= state.lines.count { it.currentlyActive }
        AlertDialog(
            onDismissRequest = { showGuard = false },
            title = {
                Text(
                    if (allActivePaused) {
                        stringResource(R.string.medication_reconcile_all_paused_confirm_title, summary.toPause.size)
                    } else {
                        stringResource(R.string.medication_reconcile_bulk_confirm_title)
                    },
                )
            },
            text = {
                Text(
                    if (allActivePaused) {
                        stringResource(R.string.medication_reconcile_all_paused_confirm_body, summary.toPause.size)
                    } else {
                        stringResource(
                            R.string.medication_reconcile_bulk_confirm_body,
                            summary.toPause.size + summary.toResume.size,
                            summary.toPause.size,
                        )
                    },
                )
            },
            confirmButton = {
                // 破坏性样式；默认动作 = 取消（dismissButton）。
                TextButton(
                    onClick = {
                        showGuard = false
                        onConfirm()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.medication_reconcile_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGuard = false
                }) { Text(stringResource(R.string.medication_reconcile_cancel)) }
            },
        )
    }
}

@Composable
private fun ResultStep(state: MedicationReconcileState, onDone: () -> Unit) {
    val result = state.result ?: return
    Text(stringResource(R.string.medication_reconcile_summary_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.medication_reconcile_result_summary, result.paused, result.resumed, result.unchanged))
    Text(stringResource(R.string.medication_status_user_checked), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.medication_reconcile_done))
    }
}

@Composable
private fun pauseNames(state: MedicationReconcileState): List<String> =
    state.summary?.toPause.orEmpty().mapNotNull { id -> state.lines.firstOrNull { it.id == id }?.name }

@Composable
private fun resumeNames(state: MedicationReconcileState): List<String> =
    state.summary?.toResume.orEmpty().mapNotNull { id -> state.lines.firstOrNull { it.id == id }?.name }

@Composable
private fun lineBadge(line: ReconcileLine): String? = when {
    line.legacyUnknown -> stringResource(R.string.medication_reconcile_legacy_unknown_badge)
    !line.currentlyActive -> stringResource(R.string.medication_status_paused)
    else -> null
}
