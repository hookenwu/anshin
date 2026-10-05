package com.driezy.medlog.feature.caretasks

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T6 照护事项 UI 契约：内容层只接收状态 + 动作 lambda，
 * 因此无需 Hilt 即可断言点击分派的 [CareTasksUiAction] / [CareTaskDetailUiAction] / [CareTaskEditorUiAction]。
 */
@RunWith(AndroidJUnit4::class)
class CareTasksFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scheduledMs = 1_700_000_000_000L

    private fun task(
        id: Long = 1L,
        title: String = "吸氧",
        completionMode: CareTaskCompletionMode = CareTaskCompletionMode.TOGGLE,
        category: String = CareTaskCategory.RESPIRATORY,
    ) = CareTask(
        id = id,
        careRecipientId = 1L,
        title = title,
        category = category,
        completionMode = completionMode,
        scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        reminderTimes = "08:00",
        startDate = scheduledMs,
    )

    @Test
    fun listDispatchesArchivedFilterAndOpen() {
        val actions = mutableListOf<CareTasksUiAction>()
        val opened = mutableListOf<Long>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTasksContent(
                    state = CareTasksUiState(
                        activeTasks = listOf(task()),
                        archivedTasks = listOf(task(id = 2, title = "旧项")),
                        isLoading = false,
                    ),
                    snackbarHostState = remember { SnackbarHostState() },
                    onAdd = {},
                    onOpen = opened::add,
                    onBack = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("吸氧").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.care_task_archived)).performClick()
        composeRule.onNodeWithText("吸氧").performClick()

        composeRule.runOnIdle {
            assertEquals(CareTasksUiAction.SetShowArchived(true), actions.single())
            assertEquals(listOf(1L), opened)
        }
    }

    @Test
    fun listFabDispatchesAdd() {
        var adds = 0
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTasksContent(
                    state = CareTasksUiState(activeTasks = listOf(task()), isLoading = false),
                    snackbarHostState = remember { SnackbarHostState() },
                    onAdd = { adds++ },
                    onOpen = {},
                    onBack = {},
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.care_task_fab_add)).performClick()

        composeRule.runOnIdle { assertEquals(1, adds) }
    }

    @Test
    fun detailDispatchesCompleteAndSkip() {
        val actions = mutableListOf<CareTaskDetailUiAction>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTaskDetailContent(
                    uiState = CareTaskDetailUiState(
                        task = task(),
                        occurrences = listOf(CareTaskOccurrenceUi(scheduledMs, "08:00", null)),
                        isLoading = false,
                    ),
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onEdit = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.care_task_action_complete)).performClick()
        composeRule.onNodeWithText(text(R.string.care_task_action_skip)).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    CareTaskDetailUiAction.Complete(scheduledMs),
                    CareTaskDetailUiAction.Skip(scheduledMs),
                ),
                actions,
            )
        }
    }

    @Test
    fun detailDispatchesUndoForHandledSlot() {
        val actions = mutableListOf<CareTaskDetailUiAction>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTaskDetailContent(
                    uiState = CareTaskDetailUiState(
                        task = task(),
                        occurrences = listOf(
                            CareTaskOccurrenceUi(scheduledMs, "08:00", CareTaskLogStatus.DONE),
                        ),
                        isLoading = false,
                    ),
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onEdit = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.care_task_action_undo)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CareTaskDetailUiAction.Undo(scheduledMs)), actions)
        }
    }

    @Test
    fun editorDispatchesCategoryChangeAndSave() {
        val actions = mutableListOf<CareTaskEditorUiAction>()
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTaskEditorContent(
                    uiState = CareTaskEditorUiState(
                        draft = CareTaskDraft(title = "吸氧", startDate = scheduledMs),
                    ),
                    onBack = {},
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.care_task_category_mobility)).performClick()
        composeRule.onNodeWithContentDescription(text(R.string.care_task_save)).performClick()

        composeRule.runOnIdle {
            assertTrue(actions.contains(CareTaskEditorUiAction.CategoryChanged(CareTaskCategory.MOBILITY)))
            assertTrue(actions.contains(CareTaskEditorUiAction.Save))
        }
    }

    @Test
    fun editorShowsValidationError() {
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                CareTaskEditorContent(
                    uiState = CareTaskEditorUiState(
                        draft = CareTaskDraft(startDate = scheduledMs),
                        validationError = CareTaskValidationError.EMPTY_TITLE,
                    ),
                    onBack = {},
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.care_task_error_title)).assertIsDisplayed()
    }

    private fun text(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
