package com.driezy.medlog.feature.carepeople

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CarePerson
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
 * 人员档案管理面 Route：列表 + 新增/编辑/删除。入口在首页「更多」溢出菜单，**不是底部 tab**
 * （docs/care-people.md §4.2）。删除走既有破坏性确认惯用法，提示引用笔记数但不阻断删除。
 */
@Composable
fun CarePeopleScreen(onBack: () -> Unit, viewModel: CarePeopleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.care_people_load_failed)

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CarePeopleUiEffect.Failed -> snackbarHostState.showSnackbar(failedMessage)
            }
        }
    }

    CarePeopleContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarePeopleContent(
    state: CarePeopleUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (CarePeopleUiAction) -> Unit,
) {
    val addLabel = stringResource(R.string.care_people_fab_add)
    val emptyState = if (!state.isLoading && state.people.isEmpty()) {
        ScreenEmptyState(
            title = stringResource(R.string.care_people_empty_title),
            body = stringResource(R.string.care_people_empty_body),
            icon = MedLogIcons.VerifiedUser,
            actionLabel = addLabel,
            actionId = "add",
        )
    } else {
        null
    }

    MedLogScreenScaffold(
        title = { Text(stringResource(R.string.care_people_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                MedLogIcon(MedLogIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        chromeState = ScreenChromeState(
            isLoading = state.isLoading,
            fab = ScreenFab("add", addLabel, MedLogIcons.Add),
            emptyState = emptyState,
        ),
        snackbarHostState = snackbarHostState,
        onChromeAction = { if (it == "add") onAction(CarePeopleUiAction.AddClicked) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(CarePeopleUiAction.QueryChanged(it)) },
                placeholder = { Text(stringResource(R.string.care_people_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MedLogSpacing.Large, vertical = MedLogSpacing.Small)
                    .testTag("carePersonSearchField"),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = MedLogSpacing.ScreenContentWithFab,
                verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Medium),
            ) {
                items(state.people, key = { it.id }) { person ->
                    PersonRow(
                        person = person,
                        modifier = Modifier.padding(horizontal = MedLogSpacing.Large),
                        onEdit = { onAction(CarePeopleUiAction.EditClicked(person.id)) },
                        onDelete = { onAction(CarePeopleUiAction.DeleteClicked(person.id)) },
                    )
                }
            }
        }
    }

    state.editor?.let { form ->
        PersonFormDialog(
            form = form,
            onDismiss = { onAction(CarePeopleUiAction.FormDismissed) },
            onChanged = { onAction(CarePeopleUiAction.FormChanged(it)) },
            onSave = { onAction(CarePeopleUiAction.FormSaved) },
        )
    }

    // 删除是破坏性操作：走 App 既有的破坏性确认惯用法；提示引用笔记数但**不阻断删除**。
    state.pendingDelete?.let { request ->
        val body = if (request.noteCount > 0) {
            stringResource(R.string.care_people_delete_body, request.noteCount)
        } else {
            stringResource(R.string.care_people_delete_body_zero)
        }
        ScreenOverlayHost(
            overlay = ScreenOverlay.Confirm(
                id = "carePerson:delete:${request.id}",
                title = stringResource(R.string.care_people_delete_title),
                body = body,
                confirmLabel = stringResource(R.string.care_people_delete),
                dismissLabel = stringResource(R.string.cancel),
                targetKey = request.id.toString(),
                isDanger = true,
            ),
            onDismiss = { onAction(CarePeopleUiAction.DeleteDismissed) },
            onConfirm = { _, _ -> onAction(CarePeopleUiAction.DeleteConfirmed) },
        )
    }
}

@Composable
private fun PersonRow(person: CarePerson, modifier: Modifier = Modifier, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = modifier.fillMaxWidth().testTag("carePersonRow:${person.id}")) {
        Text(person.name, style = MaterialTheme.typography.titleMedium)
        val secondary = listOfNotNull(
            person.gender?.takeIf { it.isNotBlank() },
            person.approxAge?.let { "$it" },
            person.hospital?.takeIf { it.isNotBlank() },
            person.agency?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (secondary.isNotBlank()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag("carePersonEdit:${person.id}"),
            ) {
                Text(stringResource(R.string.care_people_edit))
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("carePersonDelete:${person.id}"),
            ) {
                Text(stringResource(R.string.care_people_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PersonFormDialog(
    form: PersonForm,
    onDismiss: () -> Unit,
    onChanged: (PersonForm) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.id == null) R.string.care_people_add_title else R.string.care_people_edit_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MedLogSpacing.Small)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onChanged(form.copy(name = it)) },
                    label = { Text(stringResource(R.string.care_people_field_name)) },
                    isError = form.name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("carePersonNameField"),
                )
                OutlinedTextField(
                    value = form.gender,
                    onValueChange = { onChanged(form.copy(gender = it)) },
                    label = { Text(stringResource(R.string.care_people_field_gender)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.approxAge,
                    onValueChange = { onChanged(form.copy(approxAge = it)) },
                    label = { Text(stringResource(R.string.care_people_field_age)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.hospital,
                    onValueChange = { onChanged(form.copy(hospital = it)) },
                    label = { Text(stringResource(R.string.care_people_field_hospital)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.agency,
                    onValueChange = { onChanged(form.copy(agency = it)) },
                    label = { Text(stringResource(R.string.care_people_field_agency)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.phone,
                    onValueChange = { onChanged(form.copy(phone = it)) },
                    label = { Text(stringResource(R.string.care_people_field_phone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = form.name.isNotBlank(),
                modifier = Modifier.testTag("carePersonSave"),
            ) {
                Text(stringResource(R.string.care_people_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
