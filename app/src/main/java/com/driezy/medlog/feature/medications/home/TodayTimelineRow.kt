package com.driezy.medlog.feature.medications.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.feature.caretasks.careTaskCategoryLabel
import com.driezy.medlog.ui.components.MedicationCard
import com.driezy.medlog.ui.theme.MedLogSpacing
import com.driezy.medlog.ui.utils.MedLogHapticEffect
import com.driezy.medlog.ui.utils.rememberMedLogHaptics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 今日时间轴的**唯一行渲染器**（docs/care-tasks.md §4）。
 *
 * 按 [TodayItem.targetType] 分派：药物复用既有 [MedicationCard]（动作/外观逐字不变），
 * 照护事项走 [CareTaskTimelineRow]。列表容器（按时间/按分类）共用本函数，不存在第二套列表 UI。
 */
@Composable
internal fun TodayTimelineRow(
    item: TodayItem,
    flatStyle: Boolean,
    onAction: (HomeUiAction) -> Unit,
    onMedicationClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item.targetType) {
        TodayTargetType.MEDICATION -> {
            val medication = item.medication ?: return
            // 保留用药既有触感反馈（原 toggleDose/skipDose 均触发 CONFIRM）。
            val performHaptic = rememberMedLogHaptics()
            MedicationCard(
                item = medication,
                onToggleTaken = {
                    performHaptic(MedLogHapticEffect.CONFIRM)
                    onAction(HomeUiAction.ToggleDose(medication))
                },
                onSkip = {
                    performHaptic(MedLogHapticEffect.CONFIRM)
                    onAction(HomeUiAction.SkipDose(medication))
                },
                onClick = { onMedicationClick(medication.medication.id) },
                modifier = modifier.testTag("todayRow:${item.listKey}"),
                flatStyle = flatStyle,
                onPartialTake = { quantity ->
                    performHaptic(MedLogHapticEffect.CONFIRM)
                    onAction(HomeUiAction.MarkPartial(medication, quantity))
                },
            )
        }
        TodayTargetType.CARE_TASK -> CareTaskTimelineRow(item, onAction, modifier)
    }
}

/**
 * 照护事项在时间轴上的行：提供与详情页一致的完成动作（打卡/完成、开始、跳过、撤销），
 * 全部经 [HomeUiAction.CareTask*] 路由到 `CareTaskCompletionUseCase`，不直接写日志。
 */
@Composable
private fun CareTaskTimelineRow(item: TodayItem, onAction: (HomeUiAction) -> Unit, modifier: Modifier = Modifier) {
    val data = item.careTask ?: return
    val completionMode = data.task.completionMode
    val scheduledMs = item.scheduledAtMs
    val rowTag = "todayRow:${item.listKey}"
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val timeLabel = remember(scheduledMs) {
        timeFmt.format(Instant.ofEpochMilli(scheduledMs).atZone(ZoneId.systemDefault()))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(rowTag),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(MedLogSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            Text(
                text = "$timeLabel · ${item.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${careTaskCategoryLabel(item.category)} · ${careTaskStatusText(item.status)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.status) {
                    TodayItemStatus.TAKEN, TodayItemStatus.SKIPPED -> {
                        CareActionButton(
                            label = R.string.care_task_action_undo,
                            tag = "todayCareUndo:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskUndo(item.targetId, scheduledMs)) },
                        )
                    }
                    TodayItemStatus.IN_PROGRESS -> {
                        CareActionButton(
                            label = R.string.care_task_action_complete,
                            tag = "todayCareComplete:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskComplete(item.targetId, scheduledMs)) },
                        )
                        CareActionButton(
                            label = R.string.care_task_action_skip,
                            tag = "todayCareSkip:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskSkip(item.targetId, scheduledMs)) },
                        )
                        CareActionButton(
                            label = R.string.care_task_action_undo,
                            tag = "todayCareUndo:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskUndo(item.targetId, scheduledMs)) },
                        )
                    }
                    TodayItemStatus.PENDING, TodayItemStatus.PARTIAL -> {
                        if (completionMode == CareTaskCompletionMode.DURATION) {
                            CareActionButton(
                                label = R.string.care_task_action_start,
                                tag = "todayCareStart:${item.listKey}",
                                onClick = { onAction(HomeUiAction.CareTaskStart(item.targetId, scheduledMs)) },
                            )
                        }
                        CareActionButton(
                            label = R.string.care_task_action_complete,
                            tag = "todayCareComplete:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskComplete(item.targetId, scheduledMs)) },
                        )
                        CareActionButton(
                            label = R.string.care_task_action_skip,
                            tag = "todayCareSkip:${item.listKey}",
                            onClick = { onAction(HomeUiAction.CareTaskSkip(item.targetId, scheduledMs)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CareActionButton(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
private fun careTaskStatusText(status: TodayItemStatus): String = when (status) {
    TodayItemStatus.TAKEN -> stringResource(R.string.care_task_status_done)
    TodayItemStatus.SKIPPED -> stringResource(R.string.care_task_status_skipped)
    TodayItemStatus.IN_PROGRESS -> stringResource(R.string.care_task_status_in_progress)
    TodayItemStatus.PARTIAL, TodayItemStatus.PENDING -> stringResource(R.string.care_task_status_pending)
}
