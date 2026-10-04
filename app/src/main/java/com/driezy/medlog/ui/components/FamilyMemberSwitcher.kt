package com.driezy.medlog.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/** 切换器触发位置：抽屉头部 / 导航轨道头部（首页顶栏只复用 [FamilyMemberPickerDialog]）。 */
internal enum class FamilyMemberSwitcherVariant { Drawer, Rail }

/**
 * 家庭成员切换器（一级导航常驻入口）。
 *
 * 展示当前成员的称呼，点击后弹出选择器；列表为空时不渲染任何内容，
 * 保持"无成员门禁"行为完全不变（门禁由 [com.driezy.medlog.ui.MedLogApp] 负责）。
 */
@Composable
internal fun FamilyMemberSwitcher(
    recipients: List<CareRecipient>,
    activeRecipientId: Long,
    onSelectRecipient: (Long) -> Unit,
    onManageRecipients: () -> Unit,
    variant: FamilyMemberSwitcherVariant,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    if (recipients.isEmpty()) return

    val activeName = recipients.firstOrNull { it.id == activeRecipientId }?.displayName
        ?: recipients.first().displayName

    when (variant) {
        FamilyMemberSwitcherVariant.Drawer -> FamilyMemberDrawerChip(
            activeName = activeName,
            onClick = { pickerOpen = true },
            modifier = modifier,
        )

        FamilyMemberSwitcherVariant.Rail -> FamilyMemberRailChip(
            activeName = activeName,
            onClick = { pickerOpen = true },
            modifier = modifier,
        )
    }

    if (pickerOpen) {
        FamilyMemberPickerDialog(
            recipients = recipients,
            activeRecipientId = activeRecipientId,
            onSelect = { id ->
                pickerOpen = false
                onSelectRecipient(id)
            },
            onManage = {
                pickerOpen = false
                onManageRecipients()
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** 抽屉头部触发器：图标 + 当前成员称呼 + 展开箭头。 */
@Composable
internal fun FamilyMemberDrawerChip(activeName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MedLogSpacing.Medium)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MedLogSpacing.Medium, vertical = MedLogSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
        ) {
            MedLogIcon(
                MedLogIcons.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = activeName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MedLogIcon(
                MedLogIcons.ExpandMore,
                contentDescription = stringResource(R.string.recipients_switcher_title),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 轨道头部触发器：窄栏内的紧凑图标 + 称呼。 */
@Composable
internal fun FamilyMemberRailChip(activeName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.recipients_switcher_title)
    Column(
        modifier = modifier
            .padding(horizontal = MedLogSpacing.Tiny)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(horizontal = MedLogSpacing.Tiny, vertical = MedLogSpacing.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Hairline),
    ) {
        MedLogIcon(
            MedLogIcons.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = activeName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 成员选择弹窗：列出全部成员，标记当前成员，并提供"管理家庭成员"入口。 */
@Composable
internal fun FamilyMemberPickerDialog(
    recipients: List<CareRecipient>,
    activeRecipientId: Long,
    onSelect: (Long) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipients_switcher_title)) },
        text = {
            FamilyMemberPickerContent(
                recipients = recipients,
                activeRecipientId = activeRecipientId,
                onSelect = onSelect,
            )
        },
        confirmButton = {
            TextButton(onClick = onManage) {
                Text(stringResource(R.string.recipients_switcher_manage))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 无状态成员列表内容，便于预览 / 测试。 */
@Composable
internal fun FamilyMemberPickerContent(
    recipients: List<CareRecipient>,
    activeRecipientId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Hairline),
    ) {
        recipients.forEach { recipient ->
            val isActive = recipient.id == activeRecipientId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onSelect(recipient.id) }
                    .padding(horizontal = MedLogSpacing.Small, vertical = MedLogSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small),
            ) {
                MedLogIcon(
                    MedLogIcons.VerifiedUser,
                    contentDescription = null,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = recipient.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    MedLogIcon(
                        MedLogIcons.CheckCircle,
                        contentDescription = stringResource(R.string.recipients_manage_active_badge),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
