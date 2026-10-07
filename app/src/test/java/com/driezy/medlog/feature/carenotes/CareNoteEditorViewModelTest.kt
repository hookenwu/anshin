package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepositoryImpl
import com.driezy.medlog.data.repository.CareNoteTarget
import com.driezy.medlog.data.repository.FakeCareNoteDao
import com.driezy.medlog.data.repository.FakeCareTaskRepository
import com.driezy.medlog.data.repository.FakeCareTodoRepository
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 照护笔记编辑器：默认归属（最保守）、正文原样保存、挂接多目标、状态落库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CareNoteEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = FakeCareNoteDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val medications = FakeMedicationRepository()
    private val careTasks = FakeCareTaskRepository()
    private val todos = FakeCareTodoRepository()

    private fun repository() = CareNoteRepositoryImpl(
        dao,
        mock<ActiveRecipientStore> {
            on { recipientId } doReturn MutableStateFlow(1L)
            onBlocking { current() } doReturn 1L
        },
        clock,
    )

    private fun viewModel() = CareNoteEditorViewModel(repository(), medications, careTasks, todos)

    @Test
    fun `draft validation requires title and body`() {
        assertNull(validateCareNoteDraft(CareNoteDraft(title = "标题", body = "正文")))
        assertEquals(
            CareNoteValidationError.EMPTY_TITLE,
            validateCareNoteDraft(CareNoteDraft(title = " ", body = "正文")),
        )
        assertEquals(CareNoteValidationError.EMPTY_BODY, validateCareNoteDraft(CareNoteDraft(title = "标题", body = " ")))
    }

    @Test
    fun `new note keeps the conservative personal observation default and loads link options`() = runTest {
        medications.addMedication(Medication(name = "二甲双胍", doseUnit = "片"))
        careTasks.seedTask(CareTask(careRecipientId = 1L, title = "翻身"))
        todos.seed(CareTodo(careRecipientId = 1L, title = "问医生"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(CareNoteAttributionType.PERSONAL_OBSERVATION, viewModel.uiState.value.draft.attributionType)
        assertEquals(CareNoteStatus.ACTIVE, viewModel.uiState.value.draft.status)
        val types = viewModel.uiState.value.linkOptions.map { it.targetType }.toSet()
        assertEquals(setOf(CareNoteTargetType.MEDICATION, CareNoteTargetType.CARE_TASK, CareNoteTargetType.TODO), types)
    }

    @Test
    fun `save persists the verbatim body selected links and status`() = runTest {
        val medicationId = medications.addMedication(Medication(name = "二甲双胍", doseUnit = "片"))
        val viewModel = viewModel()
        advanceUntilIdle()

        val rawBody = "  我观察到饭后半小时服药比较稳  \n第二行 "
        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("护士交代"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged(rawBody))
        viewModel.onAction(CareNoteEditorUiAction.AttributionTypeChanged(CareNoteAttributionType.CLINICIAN))
        viewModel.onAction(
            CareNoteEditorUiAction.ToggleLink(CareNoteTarget(CareNoteTargetType.MEDICATION, medicationId)),
        )
        viewModel.onAction(CareNoteEditorUiAction.StatusChanged(CareNoteStatus.QUESTIONABLE))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()

        val stored = dao.stored().single()
        assertEquals("护士交代", stored.title)
        assertEquals("正文必须逐字符原样保存，绝不改写", rawBody, stored.body)
        assertEquals(CareNoteStatus.QUESTIONABLE, stored.status)
        assertEquals(CareNoteAttributionType.CLINICIAN, stored.attributionType)
        assertEquals(listOf(medicationId), dao.storedLinks().map { it.targetId })
        assertTrue(dao.storedLinks().all { it.targetType == CareNoteTargetType.MEDICATION })
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
