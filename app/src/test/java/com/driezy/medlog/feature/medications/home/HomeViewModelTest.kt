package com.driezy.medlog.feature.medications.home

import app.cash.turbine.test
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationPlanRevision
import com.driezy.medlog.data.repository.*
import com.driezy.medlog.feature.medications.application.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val meds = FakeMedicationRepository()
    private val logs = FakeLogRepository()
    private val clock = MovingClock(Instant.parse("2026-09-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val gate = CompletableDeferred<Unit>()
    private var failWrite = false

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun cleanup() = Dispatchers.resetMain()

    private fun viewModel(): HomeViewModel {
        val prefs = mock<UserPreferencesRepository> { on { settingsFlow } doReturn flowOf(SettingsPreferences()) }
        val transactions = object : TransactionRunner {
            override suspend fun <R> withTransaction(block: suspend () -> R): R {
                gate.await()
                if (failWrite) error("Database unavailable")
                return block()
            }
        }
        val dose = ToggleMedicationDoseUseCase(transactions, logs, meds, mock(), mock(), clock)
        val careTasks = mock<CareTaskRepository> {
            on { getActiveTasks() } doReturn flowOf(emptyList())
            onBlocking { getLogsForToday(any()) } doReturn emptyList()
        }
        return HomeViewModel(
            medicationRepo = meds,
            logRepo = logs,
            notificationHelper = mock(),
            toggleDoseUseCase = dose,
            importPlanUseCase = mock(),
            interactionEngine = mock(),
            prefsRepository = prefs,
            progressNotif = mock(),
            clock = clock,
            planCalculator = FuturePlanCalculator(clock),
            careTaskRepo = careTasks,
            careTaskCompletion = mock(),
            computationDispatcher = dispatcher,
        )
    }

    @Test fun `success is emitted only after storage finishes and double clicks write once`() = runTest {
        meds.addMedication(Medication(name = "药", doseUnit = "片", startDate = clock.millis(), stock = 10.0))
        val model = viewModel()
        advanceUntilIdle()
        val item = model.uiState.value.items.single()
        model.effects.test {
            model.onAction(HomeUiAction.ToggleDose(item))
            model.onAction(HomeUiAction.ToggleDose(item))
            runCurrent()
            expectNoEvents()
            assertTrue(logs.currentLogs().isEmpty())
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(awaitItem() is HomeUiEffect.DoseSaved)
            expectNoEvents()
            assertEquals(1, logs.currentLogs().size)
            assertEquals(9.0, meds.getMedicationById(item.medication.id)!!.stock!!, 0.0)
        }
    }

    @Test fun `failed write emits failure without a success receipt`() = runTest {
        meds.addMedication(Medication(name = "药", doseUnit = "片", startDate = clock.millis()))
        val model = viewModel()
        advanceUntilIdle()
        failWrite = true
        gate.complete(Unit)
        model.effects.test {
            model.onAction(HomeUiAction.ToggleDose(model.uiState.value.items.single()))
            advanceUntilIdle()
            assertTrue(awaitItem() is HomeUiEffect.Failed)
            expectNoEvents()
            assertTrue(logs.currentLogs().isEmpty())
            assertTrue(model.uiState.value.savingDoses.isEmpty())
        }
    }

    @Test fun `visible clock refresh changes daily occurrence identity and respects every N days`() = runTest {
        meds.addMedication(
            Medication(
                name = "药",
                doseUnit = "片",
                startDate = clock.millis(),
                frequencyType = "interval",
                frequencyInterval = 3,
            ),
        )
        val model = viewModel()
        advanceUntilIdle()
        val first = model.uiState.value.items.single().doseKey
        clock.now = clock.now.plus(Duration.ofDays(1))
        model.onAction(HomeUiAction.RefreshTime)
        advanceUntilIdle()
        assertTrue(model.uiState.value.items.isEmpty())
        clock.now = clock.now.plus(Duration.ofDays(2))
        model.onAction(HomeUiAction.RefreshTime)
        advanceUntilIdle()
        assertNotEquals(first, model.uiState.value.items.single().doseKey)
    }

    @Test fun `archived medication never reaches the today plan, hero or progress`() = runTest {
        meds.addMedication(Medication(name = "在服", doseUnit = "片", startDate = clock.millis()))
        val archivedId = meds.addMedication(Medication(name = "停用", doseUnit = "片", startDate = clock.millis()))
        meds.archiveMedication(archivedId)
        // 归档前留下的旧计划修订仍覆盖今日，且快照 isArchived=false——
        // 若不在此处过滤归档药品，该修订会重新投影出今日安排（用户报告的「停用药品仍出现在今日计划」）。
        meds.planRevisions.value = listOf(
            MedicationPlanRevision(
                medicationId = archivedId,
                effectiveFromMs = 0L,
                effectiveUntilMs = clock.millis() + Duration.ofDays(1).toMillis(),
                startDate = 0L,
                endDate = null,
                frequencyType = "daily",
                frequencyInterval = 1,
                frequencyDays = "1,2,3,4,5,6,7",
                timePeriod = "exact",
                reminderTimes = "08:00",
                reminderHour = 8,
                reminderMinute = 0,
                intervalHours = 0,
                isPRN = false,
                isArchived = false,
                doseQuantity = 1.0,
                doseUnit = "片",
            ),
        )

        val model = viewModel()
        advanceUntilIdle()
        val state = model.uiState.value

        assertTrue("items 含已归档药品", state.items.none { it.medication.isArchived })
        assertEquals(listOf("在服"), state.items.map { it.medication.name })
        assertEquals(1, state.overallTotal)
        assertEquals(1, state.heroPresentation.totalCount)
        assertTrue(state.todayItems.none { it.targetId == archivedId })
    }

    private class MovingClock(var now: Instant, private val timeZone: ZoneId) : Clock() {
        override fun instant() = now
        override fun getZone() = timeZone
        override fun withZone(zone: ZoneId): Clock = MovingClock(now, zone)
    }
}
