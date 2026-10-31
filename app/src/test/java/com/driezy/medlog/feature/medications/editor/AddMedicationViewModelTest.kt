package com.driezy.medlog.feature.medications.editor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.driezy.medlog.R
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.IllegalTimeOffsetException
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.data.repository.*
import com.driezy.medlog.voice.VoiceInputController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import com.driezy.medlog.domain.model.RoutineAnchor as DomainRoutineAnchor

@OptIn(ExperimentalCoroutinesApi::class)
class AddMedicationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC)
    private val transactions = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun cleanup() = Dispatchers.resetMain()

    private fun vm(
        repository: MedicationRepository,
        handle: SavedStateHandle = SavedStateHandle(),
        settings: SettingsPreferences = SettingsPreferences(),
    ): AddMedicationViewModel {
        val context = mock<Context> {
            on { getString(any<Int>()) } doAnswer { invocation -> "text:${invocation.getArgument<Int>(0)}" }
            on { getString(any<Int>(), any()) } doAnswer { invocation ->
                "fmt:" + invocation.arguments.drop(1).joinToString(",")
            }
            on { getString(R.string.default_dose_unit) } doReturn "片"
        }
        val prefs = mock<UserPreferencesRepository> { on { settingsFlow } doReturn flowOf(settings) }
        val voice = mock<VoiceInputController> {
            on { events } doReturn
                MutableSharedFlow<com.driezy.medlog.voice.VoiceInputEvent>()
        }
        val drugs = mock<DrugRepository> {
            onBlocking { searchDrugsRanked(any()) } doReturn emptyList()
        }
        return AddMedicationViewModel(repository, mock(), prefs, drugs, voice, context, clock, handle, transactions)
    }

    @Test fun `late existing medication hydration preserves user draft`() = runTest {
        val ready = CompletableDeferred<Medication>()
        val delayed = object : MedicationRepository by FakeMedicationRepository() {
            override suspend fun getMedicationById(id: Long): Medication = ready.await()
        }
        val model = vm(delayed)
        model.loadExisting(1)
        runCurrent()
        model.onAction(AddMedicationUiAction.NotesChanged("用户刚输入的草稿"))
        ready.complete(Medication(id = 1, name = "旧值", doseUnit = "片", notes = "旧备注"))
        advanceUntilIdle()
        assertEquals("用户刚输入的草稿", model.uiState.value.notes)
        assertEquals("旧值", model.uiState.value.name)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun `restored editor draft is not replaced by repeated load`() = runTest {
        val repository = FakeMedicationRepository()
        val id = repository.addMedication(Medication(name = "药品", doseUnit = "片"))
        val handle = SavedStateHandle()
        val first = vm(repository, handle)
        first.loadExisting(id)
        advanceUntilIdle()
        first.onAction(AddMedicationUiAction.NotesChanged("草稿"))
        val restored = vm(repository, handle)
        restored.loadExisting(id)
        advanceUntilIdle()
        assertEquals("草稿", restored.uiState.value.notes)
        assertTrue(restored.isDirty.value)
    }

    @Test fun `double submit saves once and uses the click snapshot`() = runTest {
        val repository = FakeMedicationRepository()
        val model = vm(repository)
        model.onAction(AddMedicationUiAction.NameChanged("药品"))
        model.onAction(AddMedicationUiAction.Save(null))
        model.onAction(AddMedicationUiAction.Save(null))
        model.onAction(AddMedicationUiAction.NameChanged("稍后输入"))
        assertTrue(model.uiState.value.isSaving)
        advanceUntilIdle()
        assertEquals("药品", repository.getActiveOnce().single().name)
    }

    @Test fun `editing notes preserves concurrent inventory and original metadata`() = runTest {
        val repository = FakeMedicationRepository()
        val id = repository.addMedication(
            Medication(name = "药品", doseUnit = "片", stock = 10.0, createdAt = 123, isArchived = true),
        )
        val model = vm(repository)
        model.loadExisting(id)
        advanceUntilIdle()
        model.onAction(AddMedicationUiAction.NotesChanged("说明"))
        repository.updateStock(id, 9.0)
        model.save(id)
        advanceUntilIdle()
        val saved = repository.getMedicationById(id)!!
        assertEquals(9.0, saved.stock!!, 0.0)
        assertEquals(123L, saved.createdAt)
        assertTrue(saved.isArchived)
    }

    @Test fun `wizard and save reject invalid drafts with visible errors`() = runTest {
        val repository = FakeMedicationRepository()
        val model = vm(repository)
        model.onAction(AddMedicationUiAction.NextStep)
        assertEquals(0, model.uiState.value.wizardStep)
        assertEquals(R.string.error_name_required, model.uiState.value.errorRes)
        model.onAction(AddMedicationUiAction.NameChanged("药品"))
        model.onAction(AddMedicationUiAction.StockChanged("NaN"))
        model.save(null)
        advanceUntilIdle()
        assertEquals(R.string.error_stock_invalid, model.uiState.value.errorRes)
        assertTrue(repository.getActiveOnce().isEmpty())
        assertEquals(
            R.string.error_date_order,
            AddMedicationUiState(name = "药", doseUnit = "片", startDate = 86_400_000, endDate = 0).validationError(),
        )
    }

    @Test
    fun `the stepper is present for a routine anchored medication and starts at zero`() = runTest {
        val model = vm(FakeMedicationRepository())
        runCurrent()
        val stepper = model.uiState.value.offsetStepper

        assertNotNull("作息时段模式的药品必须显示步进器", stepper)
        assertEquals(0, stepper!!.selectedMinutes)
        assertEquals(listOf(-60, -45, -30, -15, 0, 15, 30, 45, 60), stepper.options.map { it.minutes })
        // 精确时间模式：不显示（偏移只对作息锚点有基线）。
        model.onAction(AddMedicationUiAction.TimePeriodModeChanged(false))
        assertNull(model.uiState.value.offsetStepper)
    }

    @Test
    fun `saving a legal offset persists it and a crossing slot is refused by the stepper`() = runTest {
        val repository = FakeMedicationRepository()
        val model = vm(repository)
        model.onAction(AddMedicationUiAction.NameChanged("药品"))
        model.onAction(AddMedicationUiAction.OffsetChanged(15))

        assertEquals(15, model.uiState.value.offsetMinutes)
        // 默认作息（早餐 08:00）下 -60 合法；越界档位不会被点中。
        model.onAction(AddMedicationUiAction.OffsetChanged(999))
        assertEquals("非法档位必须被忽略，不钳位", 15, model.uiState.value.offsetMinutes)

        model.onAction(AddMedicationUiAction.Save(null))
        advanceUntilIdle()
        assertEquals(15, repository.getActiveOnce().single().offsetMinutes)
    }

    @Test
    fun `a data layer rejection uses the dedicated copy and keeps the draft`() = runTest {
        val rejecting = object : MedicationRepository by FakeMedicationRepository() {
            override suspend fun addMedication(medication: Medication): Long = throw IllegalTimeOffsetException(
                TimeOffsetCheck.Rejected(
                    minutes = 30,
                    anchor = DomainRoutineAnchor.BEDTIME,
                    base = LocalTime.of(23, 30),
                    resolvedMinuteOfDay = 24 * 60,
                    reason = TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
                ),
            )
        }
        val model = vm(rejecting)
        model.onAction(AddMedicationUiAction.NameChanged("药品"))
        model.onAction(AddMedicationUiAction.NotesChanged("草稿要保留"))

        model.onAction(AddMedicationUiAction.Save(null))
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals("必须是专用文案，不是 medication_save_failed", R.string.editor_save_offset_rejected, state.errorRes)
        assertEquals("三个参数：时段 / 偏移 / 结果钟点", 3, state.errorArgs.size)
        assertEquals("fmt:+30", state.errorArgs[1])
        assertEquals("00:00", state.errorArgs[2])
        assertFalse("不自动重试、不禁用编辑器", state.isSaving)
        assertEquals("药品", state.name)
        assertEquals("草稿要保留", state.notes)
    }

    @Test
    fun `a stored illegal offset blocks saving instead of silently resetting`() = runTest {
        val repository = FakeMedicationRepository()
        val id = repository.addMedication(
            Medication(
                name = "睡前药",
                doseUnit = "片",
                timePeriod = "bedtime",
                reminderTimes = "23:30",
                reminderHour = 23,
                reminderMinute = 30,
                offsetMinutes = 30,
            ),
        )
        val model = vm(repository, settings = SettingsPreferences(bedHour = 23, bedMinute = 30))
        model.loadExisting(id)
        advanceUntilIdle()

        assertTrue("库里已有的非法值必须显示为错误态", model.uiState.value.offsetStepper!!.storedRejected)
        model.onAction(AddMedicationUiAction.Save(id))
        advanceUntilIdle()

        assertEquals(R.string.time_offset_illegal_stored, model.uiState.value.errorRes)
        assertEquals("保存被阻止，库里的非法值一个字节都不改", 30, repository.getMedicationById(id)!!.offsetMinutes)
    }
}
