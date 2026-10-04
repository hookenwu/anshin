package com.driezy.medlog.feature.recipients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driezy.medlog.R
import com.driezy.medlog.ui.components.AnshinBrandMark
import com.driezy.medlog.ui.components.MedLogScreenScaffold
import com.driezy.medlog.ui.theme.MedLogSpacing

/**
 * 首次运行门禁：当设备上还没有任何家庭成员时，用它替代正常内容。
 * 创建第一位成员后，仓库会自动将其设为当前成员，应用随即进入正常流程。
 */
@Composable
fun CareRecipientGateScreen(viewModel: CareRecipientsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CareRecipientGateContent(
        isSaving = uiState.isSaving,
        onCreate = { name -> viewModel.onAction(CareRecipientsUiAction.Create(name)) },
    )
}

@Composable
internal fun CareRecipientGateContent(isSaving: Boolean, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }

    val isBlank = name.isBlank()
    val canSubmit = !isSaving

    fun submit() {
        if (isBlank) {
            showError = true
        } else {
            onCreate(name)
        }
    }

    MedLogScreenScaffold(title = {}, showTopBar = false) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MedLogSpacing.XLarge, vertical = MedLogSpacing.Large),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnshinBrandMark(modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(MedLogSpacing.XLarge))
            Text(
                text = stringResource(R.string.recipients_gate_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MedLogSpacing.Small))
            Text(
                text = stringResource(R.string.recipients_gate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MedLogSpacing.XLarge))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (showError && it.isNotBlank()) showError = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.recipients_gate_name_label)) },
                placeholder = { Text(stringResource(R.string.recipients_gate_name_placeholder)) },
                singleLine = true,
                isError = showError && isBlank,
                supportingText = if (showError && isBlank) {
                    { Text(stringResource(R.string.recipients_name_required)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(MedLogSpacing.Large))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.recipients_gate_save))
            }
        }
    }
}
