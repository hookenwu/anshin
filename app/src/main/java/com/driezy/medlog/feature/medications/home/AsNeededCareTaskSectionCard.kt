package com.driezy.medlog.feature.medications.home

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.feature.caretasks.careTaskCategoryLabel
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing
import kotlinx.coroutines.delay

/**
 * 「按需照护」专区卡片：只承载 [com.driezy.medlog.data.model.CareTaskScheduleKind.AS_NEEDED]
 * 的照护事项，与 [PRNSectionCard] 同构——无固定时间、不计入时间轴与总进度。
 *
 * 动作全部经 [HomeUiAction.CareTask*] 路由到 `CareTaskCompletionUseCase`（不新建记录通路）；
 * 行内动作标签带 `asNeededCare*` 前缀，便于 UI 契约测试区分于时间轴行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AsNeededCareTaskSectionCard(
    items: List<TodayItem>,
    savingKeys: Set<String>,
    onAction: (HomeUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        // 头部
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
                MedLogIcons.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_as_needed_care_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    stringResource(R.string.home_as_needed_care_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = MedLogSpacing.Medium),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        items.forEachIndexed { idx, item ->
            val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
            var visible by remember(item.targetId) { mutableStateOf(false) }
            LaunchedEffect(item.targetId, animationsEnabled) {
                if (animationsEnabled) {
                    delay(idx * STAGGER_DELAY_MS)
                }
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(motionScheme.defaultEffectsSpec()) +
                    slideInVertically(motionScheme.defaultSpatialSpec()) { it / 3 },
            ) {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(item.label, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                "${careTaskCategoryLabel(item.category)} · ${careTaskStatusText(item.status)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.isHandled) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        leadingContent = {
                            MedLogIcon(
                                MedLogIcons.Favorite,
                                contentDescription = null,
                                tint = if (item.isHandled) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                        },
                        trailingContent = {
                            AsNeededCareActions(
                                item = item,
                                saving = "${item.targetId}:${item.scheduledAtMs}" in savingKeys,
                                onAction = onAction,
                            )
                        },
                        modifier = Modifier.testTag("asNeededCareRow:${item.targetId}"),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (idx < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(MedLogSpacing.Tiny))
    }
}

@Composable
private fun AsNeededCareActions(item: TodayItem, saving: Boolean, onAction: (HomeUiAction) -> Unit) {
    val completionMode = item.careTask?.task?.completionMode
    Row(
        horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.status) {
            TodayItemStatus.TAKEN, TodayItemStatus.SKIPPED -> {
                AsNeededCareButton(
                    labelRes = R.string.care_task_action_undo,
                    tag = "asNeededCareUndo:${item.targetId}",
                    enabled = !saving,
                    onClick = { onAction(HomeUiAction.CareTaskUndo(item.targetId, item.scheduledAtMs)) },
                )
            }
            TodayItemStatus.IN_PROGRESS -> {
                AsNeededCareButton(
                    labelRes = R.string.care_task_action_complete,
                    tag = "asNeededCareComplete:${item.targetId}",
                    enabled = !saving,
                    onClick = { onAction(HomeUiAction.CareTaskComplete(item.targetId, item.scheduledAtMs)) },
                )
                AsNeededCareButton(
                    labelRes = R.string.care_task_action_undo,
                    tag = "asNeededCareUndo:${item.targetId}",
                    enabled = !saving,
                    onClick = { onAction(HomeUiAction.CareTaskUndo(item.targetId, item.scheduledAtMs)) },
                )
            }
            TodayItemStatus.PENDING, TodayItemStatus.PARTIAL -> {
                if (completionMode == CareTaskCompletionMode.DURATION) {
                    AsNeededCareButton(
                        labelRes = R.string.care_task_action_start,
                        tag = "asNeededCareStart:${item.targetId}",
                        enabled = !saving,
                        onClick = { onAction(HomeUiAction.CareTaskStart(item.targetId, item.scheduledAtMs)) },
                    )
                }
                AsNeededCareButton(
                    labelRes = R.string.care_task_action_complete,
                    tag = "asNeededCareComplete:${item.targetId}",
                    enabled = !saving,
                    onClick = { onAction(HomeUiAction.CareTaskComplete(item.targetId, item.scheduledAtMs)) },
                )
            }
        }
    }
}

@Composable
private fun AsNeededCareButton(labelRes: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(36.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = MedLogSpacing.Medium),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    }
}
