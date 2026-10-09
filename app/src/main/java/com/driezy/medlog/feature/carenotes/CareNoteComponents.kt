package com.driezy.medlog.feature.carenotes

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteLink
import com.driezy.medlog.ui.theme.MedLogSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 归属类型 key → 本地化标签。类型**恒可见**，即使名称为空（docs/care-notes.md §2）。 */
@Composable
internal fun attributionTypeLabel(type: String): String = when (type) {
    CareNoteAttributionType.CLINICIAN -> stringResource(R.string.care_note_attribution_clinician)
    CareNoteAttributionType.CAREGIVER_EXPERIENCE -> stringResource(R.string.care_note_attribution_caregiver)
    CareNoteAttributionType.EXTERNAL_MATERIAL -> stringResource(R.string.care_note_attribution_external)
    else -> stringResource(R.string.care_note_attribution_personal)
}

/**
 * 归属行：**始终**先渲染类型标签，再按需追加名称/时间/出处（docs/care-notes.md §2）。
 * 因此任何展示面都不会出现无归属的裸正文。个人观察另加中性「我观察到…」框架，绝不改成因果句。
 */
@Composable
internal fun CareNoteAttributionLine(note: CareNote, modifier: Modifier = Modifier) {
    val fields = CareNotePresentation.attributionFields(note)
    // 先在组合上下文解析字符串，再在普通 lambda 中拼装（@Composable 不能在被非组合 lambda 里调用）。
    val typeLabel = attributionTypeLabel(note.attributionType)
    val parts = buildList {
        fields.forEach { field ->
            when (field) {
                CareNoteAttributionField.TYPE -> add(typeLabel)
                CareNoteAttributionField.NAME -> note.attributionName?.takeIf { it.isNotBlank() }?.let { add(it) }
                CareNoteAttributionField.TIME -> note.attributionAtMs?.let { add(formatAttributionTime(it)) }
                CareNoteAttributionField.SOURCE -> note.attributionText?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
    val observationFrame = stringResource(R.string.care_note_observation_frame)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.care_note_attribution_prefix) + "：" + parts.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("careNoteAttribution:${note.id}"),
        )
        if (CareNotePresentation.isObservation(note)) {
            Text(
                text = observationFrame,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单条笔记卡片（就地「相关笔记」与列表共用）。
 *
 * 正文经 [CareNotePresentation.body] 原样展示，**绝不改写**。SUPERSEDED 默认折叠并标「已被更新」，
 * 展开可看 supersededText；QUESTIONABLE 显式标「待确认」。
 */
@Composable
internal fun CareNoteCard(
    note: CareNote,
    modifier: Modifier = Modifier,
    danglingLinks: List<CareNoteLink> = emptyList(),
    onRemoveDanglingLinks: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var supersededExpanded by remember { mutableStateOf(false) }
    val marker = CareNotePresentation.statusMarker(note)

    Card(
        modifier = modifier.fillMaxWidth().testTag("careNoteRow:${note.id}"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(MedLogSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            Text(note.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = CareNotePresentation.body(note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("careNoteBody:${note.id}"),
            )
            CareNoteAttributionLine(note)

            Row(
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                when (marker) {
                    CareNoteStatusMarker.QUESTIONABLE -> StatusChip(
                        stringResource(R.string.care_note_status_questionable),
                    )
                    CareNoteStatusMarker.SUPERSEDED -> StatusChip(stringResource(R.string.care_note_status_superseded))
                    CareNoteStatusMarker.NONE -> Unit
                }
                if (marker == CareNoteStatusMarker.SUPERSEDED) {
                    TextButton(
                        onClick = { supersededExpanded = !supersededExpanded },
                        modifier = Modifier.testTag("careNoteSupersededToggle:${note.id}"),
                    ) {
                        Text(
                            stringResource(
                                if (supersededExpanded) {
                                    R.string.care_note_superseded_collapse
                                } else {
                                    R.string.care_note_superseded_expand
                                },
                            ),
                        )
                    }
                }
            }

            if (marker == CareNoteStatusMarker.SUPERSEDED && supersededExpanded) {
                val timeLabel = note.supersededAtMs?.let { ms ->
                    stringResource(R.string.care_note_superseded_time_label) + "：" + formatAttributionTime(ms)
                }
                val detail = listOfNotNull(
                    note.supersededText?.takeIf { it.isNotBlank() },
                    timeLabel,
                ).joinToString("\n")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("careNoteSupersededText:${note.id}"),
                    )
                }
            }

            if (danglingLinks.isNotEmpty() && onRemoveDanglingLinks != null) {
                Text(
                    text = stringResource(R.string.care_note_dangling_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("careNoteDanglingHint:${note.id}"),
                )
                TextButton(
                    onClick = onRemoveDanglingLinks,
                    modifier = Modifier.testTag("careNoteRemoveDangling:${note.id}"),
                ) {
                    Text(stringResource(R.string.care_note_remove_link))
                }
            }

            trailing?.invoke()
        }
    }
}

@Composable
private fun StatusChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = MedLogSpacing.Small),
    )
}

/**
 * 就地「相关笔记」区块（药品详情 / 照护事项详情 / 待办详情）。
 *
 * **空态无条件不渲染任何东西**：无笔记时整块卡片缺席——无 header、无占位（docs/care-notes.md §7）。
 * 这是冻结规则，不得为了放快捷新增入口而让空态多出一个 header。
 */
@Composable
internal fun RelatedNotesSection(
    notes: List<CareNote>,
    modifier: Modifier = Modifier,
    contentPadding: Dp = MedLogSpacing.Large,
) {
    if (notes.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
    ) {
        Text(
            text = stringResource(R.string.care_note_related_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = contentPadding),
        )
        notes.forEach { note -> CareNoteCard(note, modifier = Modifier.padding(horizontal = contentPadding)) }
    }
}

/**
 * 上下文快捷新增：一条**轻量文本**入口（不是卡片、不带 header），独立坐在「相关笔记」卡片原本的位置。
 *
 * 一次点击即进入编辑器且当前对象已预挂（docs/record-center-spec.md §4.4）。它与卡片解耦，因此空态下
 * 卡片整体缺席时，「新建相关笔记」依然一次可达——两条规则不会互相打架（选项 B）。
 */
@Composable
internal fun CareNoteQuickAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = MedLogSpacing.Large,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = contentPadding).testTag("careNoteQuickAdd"),
    ) {
        Text("＋ " + stringResource(R.string.care_note_quick_add))
    }
}

private val ATTRIBUTION_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

private fun formatAttributionTime(epochMs: Long): String =
    ATTRIBUTION_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate())
