package com.driezy.medlog.feature.records

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.repository.CareNoteWithState
import com.driezy.medlog.feature.health.symptom.SymptomDiaryUiState
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 记录中心 Compose 契约：模式 chips、搜索框仅在有搜索能力的模式出现、空态不渲染列表壳。
 * 渲染 `internal` 的 [RecordsContent]（状态对象入参，不触真实数据层）。
 */
@RunWith(AndroidJUnit4::class)
class RecordsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(state: RecordsUiState, onAction: (RecordsUiAction) -> Unit = {}) {
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                RecordsContent(
                    state = state,
                    diaryState = SymptomDiaryUiState(),
                    onOpenCareNote = {},
                    onAddCareNote = {},
                    onAction = onAction,
                    onDiaryAction = {},
                )
            }
        }
    }

    private fun careNoteEntry(id: Long) = RecordEntry.CareNoteEntry(
        CareNoteWithState(
            CareNote(
                id = id,
                careRecipientId = 1L,
                title = "标题$id",
                body = "正文$id",
                attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            ),
            danglingLinks = emptyList(),
        ),
    )

    @Test
    fun modeChipsDispatchTheSelectedMode() {
        val dispatched = mutableListOf<RecordsUiAction>()
        render(
            RecordsUiState(mode = RecordsMode.ALL, isLoading = false, entries = listOf(careNoteEntry(1))),
            dispatched::add,
        )

        composeRule.onNodeWithTag("recordsMode:${RecordsMode.CARE_NOTES}").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(RecordsUiAction.SetMode(RecordsMode.CARE_NOTES)), dispatched)
        }
    }

    @Test
    fun theDiaryModeOffersNoSearchBox() {
        render(RecordsUiState(mode = RecordsMode.DIARY, isLoading = false, entries = emptyList()))

        composeRule.onNodeWithTag("recordsSearchField").assertDoesNotExist()
    }

    @Test
    fun theAllModeOffersTheCrossTypeSearchBox() {
        render(
            RecordsUiState(
                mode = RecordsMode.ALL,
                isLoading = false,
                entries = listOf(careNoteEntry(1)),
            ),
        )

        composeRule.onNodeWithTag("recordsSearchField").assertIsDisplayed()
        composeRule.onNodeWithTag("recordsMode:${RecordsMode.ALL}").assertIsDisplayed()
    }

    @Test
    fun anEmptyRecordCentreRendersOnlyTheHintAndThePlus() {
        render(RecordsUiState(mode = RecordsMode.CARE_NOTES, isLoading = false, entries = emptyList()))

        composeRule.onNodeWithTag("recordsEmptyHint").assertIsDisplayed()
        composeRule.onNodeWithTag("recordsSearchField").assertIsDisplayed()
        composeRule.onNodeWithTag("careNoteRow:1").assertDoesNotExist()
    }

    @Test
    fun theFlagOffStateShowsOnlyTheCareNotesMode() {
        render(
            RecordsUiState(
                mode = RecordsMode.CARE_NOTES,
                diaryAvailable = false,
                isLoading = false,
                entries = listOf(careNoteEntry(1)),
            ),
        )

        composeRule.onNodeWithTag("recordsMode:${RecordsMode.CARE_NOTES}").assertIsDisplayed()
        composeRule.onNodeWithTag("recordsMode:${RecordsMode.DIARY}").assertDoesNotExist()
        composeRule.onNodeWithTag("recordsMode:${RecordsMode.ALL}").assertDoesNotExist()
    }
}
