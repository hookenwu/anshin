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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 可选项加载必定失败（药品读取抛错）的 VM，用于验证错误分支。 */
    private fun failingOptionsViewModel(): CareNoteEditorViewModel {
        val failingMedications = FakeMedicationRepository().apply { failReads = true }
        return CareNoteEditorViewModel(repository(), failingMedications, careTasks, todos)
    }

    @Test
    fun `option loading success clears the loading flag so the editor form is exposed`() = runTest {
        medications.addMedication(Medication(name = "二甲双胍", doseUnit = "片"))
        val viewModel = viewModel()

        // 进入编辑器时确实先处于加载态（转圈），随后必须收敛到「已加载」。
        assertTrue("编辑器初始应处于加载态", viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(
            "可选项加载成功后 isLoading 必须为 false，否则 MainScreenChrome 只渲染转圈、表单永不出现",
            viewModel.uiState.value.isLoading,
        )
        assertEquals(1, viewModel.uiState.value.linkOptions.size)
    }

    @Test
    fun `option loading failure also clears the loading flag so the form stays usable`() = runTest {
        val viewModel = failingOptionsViewModel()
        advanceUntilIdle()

        assertFalse("加载失败也必须清除加载态，编辑器仍可用（只是没有可挂接目标）", viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.linkOptions.isEmpty())
    }

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

    @Test
    fun `delete removes the edited note cascades links and signals navigate back`() = runTest {
        val repo = repository()
        val noteId = repo.createNote(
            title = "要删除的笔记",
            body = "正文",
            attributionType = CareNoteAttributionType.PERSONAL_OBSERVATION,
            links = listOf(CareNoteTarget(CareNoteTargetType.MEDICATION, 5L)),
        )
        val viewModel = viewModel()
        viewModel.onAction(CareNoteEditorUiAction.LoadExisting(noteId))
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }

        viewModel.onAction(CareNoteEditorUiAction.Delete)
        advanceUntilIdle()

        assertNull("删除必须真正移除正在编辑的笔记", dao.storedById(noteId))
        assertTrue("删除笔记必须级联删除其 care_note_links", dao.storedLinks().isEmpty())
        assertEquals(
            "删除成功后必须发出 NavigateBack，不得停留在已不存在的笔记上",
            CareNoteEditorUiEffect.NavigateBack,
            effect.await(),
        )
    }

    @Test
    fun `delete before a note is loaded is a no-op`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.Delete)
        advanceUntilIdle()

        assertTrue("新建态没有可删除的既有笔记", dao.stored().isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
