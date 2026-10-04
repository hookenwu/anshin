package com.driezy.medlog.feature.recipients

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 0 成员 UI 契约：门禁必须阻止空名称提交，成员列表必须能标记当前成员、
 * 并在删除时先给出"数据一并删除"的确认警告。
 */
@RunWith(AndroidJUnit4::class)
class RecipientsFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gateBlocksSubmitWhenNameIsBlank() {
        val created = mutableListOf<String>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareRecipientGateContent(isSaving = false, onCreate = created::add)
            }
        }

        composeRule.onNodeWithText(text(R.string.recipients_gate_save)).performClick()

        composeRule.onNodeWithText(text(R.string.recipients_name_required)).assertIsDisplayed()
        assertTrue("空名称不得触发创建", created.isEmpty())
    }

    @Test
    fun gateSubmitsTypedName() {
        val created = mutableListOf<String>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareRecipientGateContent(isSaving = false, onCreate = created::add)
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("妈妈")
        composeRule.onNodeWithText(text(R.string.recipients_gate_save)).performClick()

        assertEquals(listOf("妈妈"), created)
    }

    @Test
    fun recipientsScreenMarksActiveMemberAndDispatchesSelection() {
        val actions = mutableListOf<CareRecipientsUiAction>()
        val dad = CareRecipient(id = 1, displayName = "爸爸")
        val mom = CareRecipient(id = 2, displayName = "妈妈")
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareRecipientsContent(
                    uiState = CareRecipientsUiState(
                        isLoading = false,
                        recipients = listOf(dad, mom),
                        activeRecipientId = dad.id,
                    ),
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("爸爸").assertIsDisplayed()
        composeRule.onNodeWithText("妈妈").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.recipients_manage_active_badge)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(text(R.string.recipients_manage_select)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CareRecipientsUiAction.SetActive(mom.id)), actions)
        }
    }

    @Test
    fun deletingRecipientAsksForConfirmationWithDataLossWarning() {
        val actions = mutableListOf<CareRecipientsUiAction>()
        val dad = CareRecipient(id = 1, displayName = "爸爸")
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareRecipientsContent(
                    uiState = CareRecipientsUiState(
                        isLoading = false,
                        recipients = listOf(dad),
                        activeRecipientId = dad.id,
                    ),
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithContentDescription(text(R.string.recipients_manage_delete)).performClick()
        composeRule.onNodeWithText(text(R.string.recipients_delete_confirm_body)).assertIsDisplayed()
        assertTrue("确认前不得删除", actions.isEmpty())

        composeRule.onNodeWithText(text(R.string.recipients_delete_confirm_action)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CareRecipientsUiAction.Delete(dad.id)), actions)
        }
    }

    private fun text(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
