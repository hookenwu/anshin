package com.driezy.medlog.feature.medications.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.driezy.medlog.R
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 首页「待办」区块（docs/todos.md §3）：安静但显眼，位于 hero 之后、时间轴之前。
 *
 * 只渲染未闭环待办（由 [HomeTodoBlock] 保证），逾期条目用醒目色——但**不响铃、不推送**。
 * 行内动作只有「完成」，撤销经由完成后的 snackbar（复用既有首页撤销交互）。
 * 空态不渲染整个区块由调用方把关（`uiState.todoBlock == null`）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeTodoSectionCard(
    block: HomeTodoBlock,
    savingIds: Set<Long>,
    onComplete: (Long) -> Unit,
    onOpenAll: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MedLogSpacing.Large,
                    end = MedLogSpacing.Medium,
                    top = MedLogSpacing.Medium,
                    bottom = MedLogSpacing.Small,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            MedLogIcon(
                MedLogIcons.DoneAll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.care_todo_block_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.care_todo_block_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = MedLogSpacing.Medium),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        block.visible.forEachIndexed { idx, row ->
            HomeTodoRowItem(
                row = row,
                saving = row.todo.id in savingIds,
                onComplete = onComplete,
            )
            if (idx < block.visible.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MedLogSpacing.Small, vertical = MedLogSpacing.Tiny),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCreate,
                modifier = Modifier.testTag("homeTodoCreate"),
            ) {
                MedLogIcon(MedLogIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(MedLogSpacing.Tiny))
                Text(stringResource(R.string.care_todo_block_create), style = MaterialTheme.typography.labelLarge)
            }
            if (block.hasMore) {
                TextButton(
                    onClick = onOpenAll,
                    modifier = Modifier.testTag("homeTodoViewAll"),
                ) {
                    Text(
                        stringResource(R.string.care_todo_block_view_all, block.totalCount),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTodoRowItem(row: HomeTodoRow, saving: Boolean, onComplete: (Long) -> Unit) {
    val todo = row.todo
    val bucket = row.bucket
    val dueLabel = when (bucket) {
        HomeTodoDueBucket.OVERDUE -> stringResource(R.string.care_todo_due_overdue)
        HomeTodoDueBucket.DUE_TODAY -> stringResource(R.string.care_todo_due_today)
        HomeTodoDueBucket.UNDATED -> null
    }
    val isOverdue = bucket == HomeTodoDueBucket.OVERDUE

    ListItem(
        headlineContent = {
            Text(todo.title, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            dueLabel?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverdue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        leadingContent = {
            MedLogIcon(
                MedLogIcons.DoneAll,
                contentDescription = null,
                tint = if (isOverdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        trailingContent = {
            FilledTonalButton(
                onClick = { onComplete(todo.id) },
                enabled = !saving,
                modifier = Modifier.height(36.dp).testTag("homeTodoComplete:${todo.id}"),
                contentPadding = PaddingValues(horizontal = MedLogSpacing.Medium),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                MedLogIcon(MedLogIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(MedLogSpacing.Tiny))
                Text(stringResource(R.string.care_todo_action_complete), style = MaterialTheme.typography.labelMedium)
            }
        },
        modifier = Modifier.testTag("homeTodoRow:${todo.id}"),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
