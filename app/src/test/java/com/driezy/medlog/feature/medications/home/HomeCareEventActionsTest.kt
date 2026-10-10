package com.driezy.medlog.feature.medications.home

import app.cash.turbine.test
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.model.CareEventLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareEventRepositoryImpl
import com.driezy.medlog.data.repository.FakeCareEventLogDao
import com.driezy.medlog.data.repository.FakeCareTodoRepository
import com.driezy.medlog.data.repository.FakeLogRepository
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.medications.application.FuturePlanCalculator
import com.driezy.medlog.feature.medications.application.ToggleMedicationDoseUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 照护事件在今日页的**补记 / 编辑 / 删除**与 entries 面板（docs/tracked-events-spec.md §6 D4/R11）。
 *
 * 写入经**真实** [CareEventRepositoryImpl]（不是桩）：编辑/删除落库后，派生状态由仓库的 flow 重算，
 * 从而证明「重算 - 绝不即时通知」的语义是被继承而非在 UI 层重实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCareEventActionsTest {

    private val dispatcher = StandardTestDispatcher()
    private val meds = FakeMedicationRepository()
    private val logs = FakeLogRepository()
    private val todos = FakeCareTodoRepository()
    private val dao = FakeCareEventLogDao()
    private val clock = MovingClock(Instant.parse("2026-09-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val notificationHelper = mock<com.driezy.medlog.capability.reminders.NotificationHelper>()

    private val now get() = clock.millis()
    private val day = 86_400_000L

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun cleanup() = Dispatchers.resetMain()

    private fun careRepo(): CareEventRepositoryImpl {
        val store: ActiveRecipientStore = mock {
            on { recipientId } doReturn MutableStateFlow(1L)
            onBlocking { current() } doReturn 1L
        }
        return CareEventRepositoryImpl(dao, store, clock)
    }

    private fun viewModel(): HomeViewModel {
        val prefs = mock<UserPreferencesRepository> { on { settingsFlow } doReturn flowOf(SettingsPreferences()) }
        val transactions = object : TransactionRunner {
            override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
        }
        val dose = ToggleMedicationDoseUseCase(transactions, logs, meds, mock(), mock(), clock)
        val careTasks = mock<com.driezy.medlog.data.repository.CareTaskRepository> {
            on { getActiveTasks() } doReturn flowOf(emptyList())
            onBlocking { getLogsForToday(any()) } doReturn emptyList()
        }
        return HomeViewModel(
            medicationRepo = meds,
            logRepo = logs,
            notificationHelper = notificationHelper,
            toggleDoseUseCase = dose,
            importPlanUseCase = mock(),
            interactionEngine = mock(),
            prefsRepository = prefs,
            progressNotif = mock(),
            clock = clock,
            planCalculator = FuturePlanCalculator(clock),
            careTaskRepo = careTasks,
            careTaskCompletion = mock(),
            careTodoRepository = todos,
            careEventRepository = careRepo(),
            computationDispatcher = dispatcher,
        )
    }

    private fun seed(occurredAtMs: Long, note: String? = null): Long = dao.seed(
        CareEventLog(
            careRecipientId = 1L,
            kind = CareEventKind.BOWEL,
            occurredAtMs = occurredAtMs,
            note = note,
            createdAtMs = occurredAtMs,
        ),
    )

    // ── GAP3：无计划且零记录也能记下第一条 ───────────────────────────────

    @Test
    fun `a member with no plan and no events can record a first event`() = runTest {
        val model = viewModel()
        advanceUntilIdle()

        // 冷启动：无用药计划、零记录——状态行仍有承载位（空态），记录入口可达。
        val coldStart = model.uiState.value
        assertEquals("无用药计划", 0, coldStart.overallTotal)
        assertTrue("状态必须已观察到（空态），否则状态行不渲染", coldStart.careEventStatus != null)
        assertFalse(coldStart.careEventStatus!!.hasAnyRecord)
        assertTrue(coldStart.careEventEntries.isEmpty())

        model.onAction(HomeUiAction.RecordCareEventNow)
        advanceUntilIdle()

        val after = model.uiState.value
        assertEquals("第一条记录落库", 1, after.careEventEntries.size)
        assertTrue(after.careEventStatus!!.hasAnyRecord)
        assertEquals(0L, after.careEventStatus!!.daysSince)
        verifyNoInteractions(notificationHelper)
    }

    // ── GAP1：entries 面可达 ──────────────────────────────────────────────

    @Test
    fun `the entries surface opens and closes and lists the current member records`() = runTest {
        seed(occurredAtMs = now - 2 * day, note = "餐后")
        val model = viewModel()
        advanceUntilIdle()

        assertFalse(model.uiState.value.careEventEntriesOpen)
        model.onAction(HomeUiAction.OpenCareEventEntries)
        advanceUntilIdle()

        val open = model.uiState.value
        assertTrue("状态行可打开记录列表，无需新 Tab", open.careEventEntriesOpen)
        assertEquals(1, open.careEventEntries.size)
        assertEquals("餐后", open.careEventEntries.single().note)

        model.onAction(HomeUiAction.CloseCareEventEntries)
        advanceUntilIdle()
        assertFalse(model.uiState.value.careEventEntriesOpen)
    }

    // ── GAP1：编辑 → 重算、绝不即时通知 ──────────────────────────────────

    @Test
    fun `editing an entry through the ui recomputes the interval and never notifies`() = runTest {
        val id = seed(occurredAtMs = now - 5 * day)
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(5L, model.uiState.value.careEventStatus!!.daysSince)

        model.effects.test {
            model.onAction(HomeUiAction.EditCareEvent(id, occurredAtMs = now, note = "改正"))
            advanceUntilIdle()
            expectNoEvents() // 历史变更不产生任何回执/通知（R11）
        }

        val stored = dao.storedById(id)!!
        assertEquals(now, stored.occurredAtMs)
        assertEquals("改正", stored.note)
        assertEquals("编辑置 updatedAtMs", now, stored.updatedAtMs)
        assertEquals("就地编辑，锚点前移 → 间隔重算为 0 天", 0L, model.uiState.value.careEventStatus!!.daysSince)
        verifyNoInteractions(notificationHelper)
    }

    // ── GAP1：删除 → 锚点回退 / 清空，重算、绝不即时通知 ─────────────────

    @Test
    fun `deleting entries recomputes back to the previous anchor then to none`() = runTest {
        val older = seed(occurredAtMs = now - 5 * day)
        val newest = seed(occurredAtMs = now - 2 * day)
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.careEventStatus!!.daysSince)

        model.effects.test {
            model.onAction(HomeUiAction.DeleteCareEvent(newest))
            advanceUntilIdle()
            expectNoEvents()
        }
        assertNull(dao.storedById(newest))
        assertEquals("删最新 → 锚点回退次新并重算", 5L, model.uiState.value.careEventStatus!!.daysSince)

        model.onAction(HomeUiAction.DeleteCareEvent(older))
        advanceUntilIdle()
        assertFalse("删到无记录 → 无锚点", model.uiState.value.careEventStatus!!.hasAnyRecord)
        assertNull(model.uiState.value.careEventStatus!!.daysSince)
        verifyNoInteractions(notificationHelper)
    }

    // ── GAP1：补记（发生时刻在过去）经 UI 可达 ───────────────────────────

    @Test
    fun `back-dating through the ui keeps occurredAt in the past and entry time separate`() = runTest {
        val model = viewModel()
        advanceUntilIdle()

        model.onAction(HomeUiAction.RecordCareEventAt(occurredAtMs = now - 3 * day, note = "补记"))
        advanceUntilIdle()

        val stored = dao.stored().single()
        assertEquals("补记保留过去的发生时刻", now - 3 * day, stored.occurredAtMs)
        assertEquals("录入时刻为现在", now, stored.createdAtMs)
        assertEquals("补记", stored.note)
        assertEquals(3L, model.uiState.value.careEventStatus!!.daysSince)
        verifyNoInteractions(notificationHelper)
    }

    private class MovingClock(var now: Instant, private val timeZone: ZoneId) : Clock() {
        override fun instant() = now
        override fun getZone() = timeZone
        override fun withZone(zone: ZoneId): Clock = MovingClock(now, zone)
    }
}
