package com.driezy.medlog.feature.carepeople

import com.driezy.medlog.data.model.CarePerson
import com.driezy.medlog.data.repository.FakeCarePersonRepository
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 人员档案管理面：列表/搜索、新增/编辑，以及**删除提示引用笔记数但不阻断删除**
 * （docs/care-people.md §4.2/§9.2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarePeopleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCarePersonRepository()

    @Test
    fun `list is sorted by name and search narrows by substring`() = runTest {
        repository.seed(CarePerson(careRecipientId = 1L, name = "王医生"))
        repository.seed(CarePerson(careRecipientId = 1L, name = "护士张"))

        val viewModel = CarePeopleViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("护士张", "王医生"), viewModel.uiState.value.people.map { it.name })

        viewModel.onAction(CarePeopleUiAction.QueryChanged("护"))
        advanceUntilIdle()
        assertEquals(listOf("护士张"), viewModel.uiState.value.people.map { it.name })
    }

    @Test
    fun `save creates a person with only a name and edit updates it`() = runTest {
        val viewModel = CarePeopleViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CarePeopleUiAction.AddClicked)
        viewModel.onAction(CarePeopleUiAction.FormChanged(PersonForm(name = "护士张")))
        viewModel.onAction(CarePeopleUiAction.FormSaved)
        advanceUntilIdle()

        val created = repository.stored().single()
        assertEquals("护士张", created.name)
        assertNull("保存后关闭表单", viewModel.uiState.value.editor)

        viewModel.onAction(CarePeopleUiAction.EditClicked(created.id))
        advanceUntilIdle()
        assertEquals("护士张", viewModel.uiState.value.editor?.name)

        viewModel.onAction(CarePeopleUiAction.FormChanged(PersonForm(id = created.id, name = "张护士", approxAge = "30")))
        viewModel.onAction(CarePeopleUiAction.FormSaved)
        advanceUntilIdle()
        assertEquals("张护士", repository.storedById(created.id)!!.name)
        assertEquals(30, repository.storedById(created.id)!!.approxAge)
    }

    @Test
    fun `delete prompts with the referencing note count and does not block the deletion`() = runTest {
        val personId = repository.seed(CarePerson(careRecipientId = 1L, name = "护士张"))
        repository.setNoteCount(personId, 3)

        val viewModel = CarePeopleViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CarePeopleUiAction.DeleteClicked(personId))
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingDelete!!
        assertEquals(personId, pending.id)
        assertEquals("护士张", pending.name)
        assertEquals("必须提示引用该人员的笔记数", 3, pending.noteCount)
        // 尚未确认：人员仍在
        assertEquals(1, repository.stored().size)

        viewModel.onAction(CarePeopleUiAction.DeleteConfirmed)
        advanceUntilIdle()
        assertNull("删除后人员行被移除（不因引用而阻断）", repository.storedById(personId))
        assertNull(viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `dismissing a delete keeps the person`() = runTest {
        val personId = repository.seed(CarePerson(careRecipientId = 1L, name = "王医生"))

        val viewModel = CarePeopleViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onAction(CarePeopleUiAction.DeleteClicked(personId))
        advanceUntilIdle()
        viewModel.onAction(CarePeopleUiAction.DeleteDismissed)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingDelete)
        assertEquals(1, repository.stored().size)
    }
}
