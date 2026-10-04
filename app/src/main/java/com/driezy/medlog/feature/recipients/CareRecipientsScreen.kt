package com.driezy.medlog.feature.recipients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.components.ScreenChromeState
import com.driezy.medlog.ui.components.ScreenEmptyState
import com.driezy.medlog.ui.components.ScreenFab
import com.driezy.medlog.ui.components.ScreenOverlay
import com.driezy.medlog.ui.components.ScreenOverlayHost
import com.driezy.medlog.ui.icons.MedLogIcon
import com.driezy.medlog.ui.icons.MedLogIcons
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 家庭成员管理页（设置入口）：列出全部成员，支持新增、重命名、删除（带删除警告）
 * 以及切换当前成员；当前成员以卡片底色 + 徽标 + 勾选图标标记。
 */
@Composable
fun CareRecipientsScreen(onBack: () -> Unit, viewModel: CareRecipientsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val renameFailedMessage = stringResource(R.string.recipients_rename_failed)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CareRecipientsUiEffect.RenameFailed -> snackbarHostState.showSnackbar(renameFailedMessage)
            }
        }
    }

    CareRecipientsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@Composable
internal fun CareRecipientsContent(
    uiState: CareRecipientsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (CareRecipientsUiAction) -> Unit,
) {
    var overlay by remember { mutableStateOf<ScreenOverlay?>(null) }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }

    val manageTitle = stringResource(R.string.recipients_manage_title)
    val addLabel = stringResource(R.string.recipients_manage_add)
    val nameLabel = stringResource(R.string.recipients_name_label)
    val saveLabel = stringResource(R.string.common_save)
    val cancelLabel = stringResource(R.string.cancel)
    val deleteLabel = stringResource(R.string.recipients_manage_delete)
    val deleteConfirmLabel = stringResource(R.string.recipients_delete_confirm_action)
    val renameLabel = stringResource(R.string.recipients_manage_rename)
    val createDialogTitle = stringResource(R.string.recipients_create_dialog_title)
    val renameDialogTitle = stringResource(R.string.recipients_rename_dialog_title)
    val deleteDialogTitle = stringResource(R.string.recipients_delete_confirm_title)
    val deleteDialogBody = stringResource(R.string.recipients_delete_confirm_body)

    fun openCreateDialog() {
        overlay = ScreenOverlay.TextInput(
            id = "create",
            title = createDialogTitle,
            label = nameLabel,
            confirmLabel = addLabel,
            dismissLabel = cancelLabel,
        )
    }

    val isEmpty = !uiState.isLoading && uiState.recipients.isEmpty()

    MedLogScreenScaffold(
        title = { Text(manageTitle) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                MedLogIcon(
                    MedLogIcons.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
        },
        chromeState = ScreenChromeState(
            isLoading = uiState.isLoading,
            fab = ScreenFab("add", addLabel, MedLogIcons.Add),
            emptyState = if (isEmpty) {
                ScreenEmptyState(
                    title = stringResource(R.string.recipients_manage_empty_title),
                    body = stringResource(R.string.recipients_manage_empty_body),
                    icon = MedLogIcons.VerifiedUser,
                    actionLabel = addLabel,
                    actionId = "add",
                )
            } else {
                null
            },
        ),
        snackbarHostState = snackbarHostState,
        onChromeAction = { action ->
            if (action == "add") openCreateDialog()
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = MedLogSpacing.ScreenContentWithFab,
            verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
        ) {
            items(uiState.recipients, key = { it.id }) { recipient ->
                CareRecipientRow(
                    recipient = recipient,
                    isActive = recipient.id == uiState.activeRecipientId,
                    activeBadge = stringResource(R.string.recipients_manage_active_badge),
                    selectLabel = stringResource(R.string.recipients_manage_select),
                    renameLabel = renameLabel,
                    deleteLabel = deleteLabel,
                    onSelect = { onAction(CareRecipientsUiAction.SetActive(recipient.id)) },
                    onRename = {
                        renameTargetId = recipient.id
                        overlay = ScreenOverlay.TextInput(
                            id = "rename",
                            title = renameDialogTitle,
                            label = nameLabel,
                            confirmLabel = saveLabel,
                            dismissLabel = cancelLabel,
                            initialValue = recipient.displayName,
                        )
                    },
                    onDelete = {
                        overlay = ScreenOverlay.Confirm(
                            id = "delete",
                            title = deleteDialogTitle,
                            body = deleteDialogBody,
                            confirmLabel = deleteConfirmLabel,
                            dismissLabel = cancelLabel,
                            targetKey = recipient.id.toString(),
                            isDanger = true,
                        )
                    },
                )
            }
        }
    }

    ScreenOverlayHost(
        overlay = overlay,
        onDismiss = {
            overlay = null
            renameTargetId = null
        },
        onConfirm = { resolved, value ->
            when (resolved.id) {
                "create" -> value?.let { onAction(CareRecipientsUiAction.Create(it)) }
                "rename" -> {
                    val id = renameTargetId
                    if (id != null) value?.let { onAction(CareRecipientsUiAction.Rename(id, it)) }
                }
                "delete" -> (resolved as? ScreenOverlay.Confirm)?.targetKey?.toLongOrNull()
                    ?.let { onAction(CareRecipientsUiAction.Delete(it)) }
            }
        },
    )
}

@Composable
private fun CareRecipientRow(
    recipient: CareRecipient,
    isActive: Boolean,
    activeBadge: String,
    selectLabel: String,
    renameLabel: String,
    deleteLabel: String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Medium),
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Hairline),
            ) {
                Text(
                    text = recipient.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = if (isActive) activeBadge else selectLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isActive) {
                MedLogIcon(
                    MedLogIcons.CheckCircle,
                    contentDescription = activeBadge,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                IconButton(onClick = onSelect) {
                    MedLogIcon(
                        MedLogIcons.CheckCircle,
                        contentDescription = selectLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRename) {
                MedLogIcon(
                    MedLogIcons.Edit,
                    contentDescription = renameLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                MedLogIcon(
                    MedLogIcons.Delete,
                    contentDescription = deleteLabel,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
