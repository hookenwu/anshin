package com.driezy.medlog.feature.carenotes

import com.driezy.medlog.data.local.CareNoteDao
import com.driezy.medlog.data.model.CareNote
import com.driezy.medlog.data.model.CareNoteAttributionType
import com.driezy.medlog.data.model.CareNoteStatus
import com.driezy.medlog.data.model.CareNoteTargetType
import com.driezy.medlog.data.model.CarePerson
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareNoteRepositoryImpl
import com.driezy.medlog.data.repository.CareNoteTarget
import com.driezy.medlog.data.repository.FakeCareNoteDao
import com.driezy.medlog.data.repository.FakeCarePersonRepository
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
    private val carePersons = FakeCarePersonRepository()

    private fun store(activeId: Long = 1L): ActiveRecipientStore = mock {
        on { recipientId } doReturn MutableStateFlow(activeId)
        onBlocking { current() } doReturn activeId
    }

    private fun repository(noteDao: CareNoteDao = dao, activeId: Long = 1L) = CareNoteRepositoryImpl(
        noteDao,
        store(activeId),
        clock,
    )

    private fun viewModel(activeId: Long = 1L) = CareNoteEditorViewModel(
        repository(activeId = activeId),
        medications,
        careTasks,
        todos,
        carePersons,
        store(activeId),
    )

    /** 可选项加载必定失败（药品读取抛错）的 VM，用于验证错误分支。 */
    private fun failingOptionsViewModel(): CareNoteEditorViewModel {
        val failingMedications = FakeMedicationRepository().apply { failReads = true }
        return CareNoteEditorViewModel(repository(), failingMedications, careTasks, todos, carePersons, store())
    }

    /** 写入必抛错的 DAO：读委托给内存假件，用于验证保存失败时的可见反馈。 */
    private class ThrowingWriteDao : CareNoteDao by FakeCareNoteDao() {
        override suspend fun insert(note: CareNote): Long = error("模拟写入失败")
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

    @Test
    fun `loading a note that does not exist emits a failure effect`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }

        viewModel.onAction(CareNoteEditorUiAction.LoadExisting(noteId = 999L))
        advanceUntilIdle()

        assertTrue(
            "加载失败的笔记必须发出 Failed 效果，供 Route 呈现给用户（此前被静默吞掉）",
            effect.await() is CareNoteEditorUiEffect.Failed,
        )
    }

    @Test
    fun `a save failure emits a failure effect and clears the saving flag`() = runTest {
        val viewModel =
            CareNoteEditorViewModel(repository(ThrowingWriteDao()), medications, careTasks, todos, carePersons, store())
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }

        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged("正文"))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()

        assertTrue(
            "保存失败必须发出 Failed 效果，供 Route 呈现给用户（此前被静默吞掉）",
            effect.await() is CareNoteEditorUiEffect.Failed,
        )
        assertFalse("保存失败后必须复位 isSaving，否则保存按钮永久禁用", viewModel.uiState.value.isSaving)
    }

    // ── 上下文快捷新增的成员校验（硬规则）────────────────────────────────────

    @Test
    fun `quick add pre-attaches the link when the target belongs to the active member`() = runTest {
        val medicationId = medications.addMedication(
            Medication(name = "二甲双胍", doseUnit = "片", careRecipientId = 1L),
        )
        val viewModel = viewModel(activeId = 1L)
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.PreloadQuickAddLink(CareNoteTargetType.MEDICATION, medicationId))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.quickAddRefused)
        assertEquals(
            setOf(CareNoteTarget(CareNoteTargetType.MEDICATION, medicationId)),
            viewModel.uiState.value.draft.links,
        )
    }

    @Test
    fun `quick add refuses a cross member link for every target type`() = runTest {
        // 目标属于其他成员（2L），当前成员是 1L。
        val medicationId = medications.addMedication(
            Medication(name = "别人的药", doseUnit = "片", careRecipientId = 2L),
        )
        val taskId = careTasks.seedTask(CareTask(careRecipientId = 2L, title = "别人的照护事项"))
        val todoId = todos.seed(CareTodo(careRecipientId = 2L, title = "别人的待办"))

        listOf(
            CareNoteTargetType.MEDICATION to medicationId,
            CareNoteTargetType.CARE_TASK to taskId,
            CareNoteTargetType.TODO to todoId,
        ).forEach { (type, id) ->
            val viewModel = viewModel(activeId = 1L)
            advanceUntilIdle()

            viewModel.onAction(CareNoteEditorUiAction.PreloadQuickAddLink(type, id))
            advanceUntilIdle()

            assertTrue("跨成员目标必须被拒绝并给出中性提示", viewModel.uiState.value.quickAddRefused)
            assertTrue("绝不预挂跨成员关联", viewModel.uiState.value.draft.links.isEmpty())
        }
    }

    @Test
    fun `quick add with a cross member target saves without any link`() = runTest {
        val medicationId = medications.addMedication(
            Medication(name = "别人的药", doseUnit = "片", careRecipientId = 2L),
        )
        val viewModel = viewModel(activeId = 1L)
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.PreloadQuickAddLink(CareNoteTargetType.MEDICATION, medicationId))
        advanceUntilIdle()
        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged("正文"))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()

        assertEquals("笔记仍可保存", 1, dao.stored().size)
        assertTrue("保存后仍不得产生任何跨成员关联", dao.storedLinks().isEmpty())
    }

    @Test
    fun `quick add does not pre-attach when there is no active member`() = runTest {
        val medicationId = medications.addMedication(
            Medication(name = "二甲双胍", doseUnit = "片", careRecipientId = 1L),
        )
        val viewModel = viewModel(activeId = ActiveRecipientStore.NO_RECIPIENT)
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.PreloadQuickAddLink(CareNoteTargetType.MEDICATION, medicationId))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.quickAddRefused)
        assertTrue(viewModel.uiState.value.draft.links.isEmpty())
    }

    // ── 「谁说的」选择器 / 快速新增 / 自由输入 + 三条边界规则（docs/care-people.md §2/§4.1）──

    @Test
    fun `selecting a person fills the name and id without changing the attribution type`() = runTest {
        val personId = carePersons.seed(CarePerson(careRecipientId = 1L, name = "护士张"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(CareNoteAttributionType.PERSONAL_OBSERVATION, viewModel.uiState.value.draft.attributionType)
        viewModel.onAction(CareNoteEditorUiAction.AttributionTypeChanged(CareNoteAttributionType.CLINICIAN))
        viewModel.onAction(CareNoteEditorUiAction.AttributionPersonSelected(personId, "护士张"))
        advanceUntilIdle()

        assertEquals("护士张", viewModel.uiState.value.draft.attributionName)
        assertEquals(personId, viewModel.uiState.value.draft.attributionPersonId)
        assertEquals(
            "选择人员不得设置或锁定归属类型（类型只属于笔记）",
            CareNoteAttributionType.CLINICIAN,
            viewModel.uiState.value.draft.attributionType,
        )
    }

    @Test
    fun `hand editing the name detaches the person while keeping the typed name`() = runTest {
        val personId = carePersons.seed(CarePerson(careRecipientId = 1L, name = "护士张"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.AttributionPersonSelected(personId, "护士张"))
        advanceUntilIdle()
        assertEquals(personId, viewModel.uiState.value.draft.attributionPersonId)

        viewModel.onAction(CareNoteEditorUiAction.AttributionNameChanged("张护工"))
        advanceUntilIdle()

        assertEquals("用户手改的名字必须保留", "张护工", viewModel.uiState.value.draft.attributionName)
        assertNull("手动改姓名必须自动解除人员关联", viewModel.uiState.value.draft.attributionPersonId)
    }

    @Test
    fun `quick add creates a name only person selects it and stays in the editor`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.QuickAddPerson("新护工"))
        advanceUntilIdle()

        val created = carePersons.stored().single()
        assertEquals("快速新增只要求姓名", "新护工", created.name)
        assertEquals("新增后自动选中", created.id, viewModel.uiState.value.draft.attributionPersonId)
        assertEquals("新护工", viewModel.uiState.value.draft.attributionName)
        // 不离开编辑流程：草稿仍在，可直接继续填写并保存。
        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged("正文"))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()
        assertEquals(1, dao.stored().size)
    }

    @Test
    fun `picker search narrows by name and a note still saves as free text without any person`() = runTest {
        carePersons.seed(CarePerson(careRecipientId = 1L, name = "护士张"))
        carePersons.seed(CarePerson(careRecipientId = 1L, name = "王医生"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(setOf("护士张", "王医生"), viewModel.uiState.value.personOptions.map { it.name }.toSet())

        viewModel.onAction(CareNoteEditorUiAction.AttributionNameChanged("护士"))
        advanceUntilIdle()
        assertEquals(listOf("护士张"), viewModel.uiState.value.personOptions.map { it.name })

        // 自由输入路径完全保留：不选人员也能保存（无人员记录）。
        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged("正文"))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()

        val stored = dao.stored().single()
        assertNull("自由输入不得凭空产生人员关联", stored.attributionPersonId)
        assertEquals("护士", stored.attributionName)
    }

    @Test
    fun `saving persists the person association and clears it once the person is gone`() = runTest {
        dao.seedPerson(5L, recipientId = 1L)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAction(CareNoteEditorUiAction.AttributionPersonSelected(5L, "护士张"))
        viewModel.onAction(CareNoteEditorUiAction.TitleChanged("标题"))
        viewModel.onAction(CareNoteEditorUiAction.BodyChanged("正文"))
        viewModel.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()

        val noteId = dao.stored().single().id
        assertEquals(5L, dao.storedById(noteId)!!.attributionPersonId)

        // 人员被删除 → 悬挂引用读取时被容忍；再次保存时确定性清空，姓名快照保留。
        dao.removePerson(5L)
        val reopened = viewModel()
        reopened.onAction(CareNoteEditorUiAction.LoadExisting(noteId))
        advanceUntilIdle()
        assertEquals("读取容忍：悬挂 id 仍原样加载", 5L, reopened.uiState.value.draft.attributionPersonId)

        reopened.onAction(CareNoteEditorUiAction.Save)
        advanceUntilIdle()
        val saved = dao.storedById(noteId)!!
        assertNull("人员不存在时保存必须不写入关联", saved.attributionPersonId)
        assertEquals("护士张", saved.attributionName)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
