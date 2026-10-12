package com.driezy.medlog.feature.medications.reconcile

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.FakeMedicationStatusPreferences
import com.driezy.medlog.data.repository.FakeMedicationStatusRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.medications.application.MedicationStatus
import com.driezy.medlog.feature.medications.application.MedicationStatusDerivation
import com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 核对编排（T3/T7）——docs/medication-daily-reconcile.md §2 D3/D5/D7、§5、§6、§7 E9/E16、
 * §9 C1/C2/C4/C7/C10/C11/C12/C14/C16、§10。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationReconcileUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 19)

    private class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private val transactionRunner = object : TransactionRunner {
        override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
    }

    private lateinit var medications: FakeMedicationRepository
    private lateinit var statusRepo: FakeMedicationStatusRepository
    private lateinit var prefs: FakeMedicationStatusPreferences
    private lateinit var clock: MutableClock
    private lateinit var activeRecipient: ActiveRecipientStore
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var reconcile: ReconcileRemindersUseCase

    private fun localStart(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        prefs = FakeMedicationStatusPreferences()
        clock = MutableClock(Instant.parse("2026-09-19T06:00:00Z"), zone) // 14:00 本地
        activeRecipient = mock()
        whenever(activeRecipient.current()).thenReturn(1L)
        reconcile = mock()
        userPreferences = mock()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
    }

    private suspend fun seed(name: String): Long {
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = name, doseUnit = "片"))
        return medications.getAllMedicationsFor(1L).last().id
    }

    private suspend fun pause(id: Long, at: LocalDate = day) {
        statusRepo.insertEvent(
            MedicationStatusEvent(
                medicationId = id,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = localStart(at),
                recordedAtMs = id,
            ),
        )
    }

    private fun viewModel(): MedicationDailyReconcileViewModel {
        val useCase = SetMedicationStatusUseCase(
            medications,
            statusRepo,
            prefs,
            userPreferences,
            activeRecipient,
            transactionRunner,
            reconcile,
            clock,
        )
        return MedicationDailyReconcileViewModel(
            medications,
            statusRepo,
            useCase,
            userPreferences,
            activeRecipient,
            clock,
        )
    }

    private fun TestScope.settled(vm: MedicationDailyReconcileViewModel): MedicationDailyReconcileViewModel {
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `C1 opening and confirming with no changes writes no events but records today`() = runTest {
        seed("A")
        seed("B")
        val paused = seed("C").also { pause(it) }
        val vm = settled(viewModel())

        assertEquals("活跃预勾选", true, vm.state.value.lines.single { it.name == "A" }.checked)
        assertEquals("已停用预不勾选", false, vm.state.value.lines.single { it.name == "C" }.checked)
        assertEquals(1, statusRepo.events.size)

        vm.toSummary()
        advanceUntilIdle()
        val summary = vm.state.value.summary!!
        assertTrue(summary.toPause.isEmpty())
        assertTrue(summary.toResume.isEmpty())
        assertFalse(summary.needsSecondConfirmation)

        vm.confirm()
        advanceUntilIdle()
        assertEquals(ReconcileStep.RESULT, vm.state.value.step)
        assertEquals("零状态事件", 1, statusRepo.events.size)
        assertEquals("记今天已核对", day.toEpochDay(), prefs.userCheckedDate(1L))
        assertEquals(paused, paused)
    }

    @Test
    fun `C2 uncheck then recheck before confirm writes no events`() = runTest {
        val a = seed("A")
        val vm = settled(viewModel())
        vm.onToggle(a)
        vm.onToggle(a) // back to default
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals(0, statusRepo.events.size)
        assertEquals(day.toEpochDay(), prefs.userCheckedDate(1L))
    }

    @Test
    fun `C4 an untouched medication gets no event and stays active tomorrow`() = runTest {
        seed("A")
        val b = seed("B")
        val vm = settled(viewModel())
        val a = vm.state.value.lines.single { it.name == "A" }.id
        vm.onToggle(a) // only A is paused
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals(1, statusRepo.events.size)
        assertEquals(
            MedicationStatus.ACTIVE,
            MedicationStatusDerivation.statusAt(
                statusRepo.events.filter {
                    it.medicationId ==
                        b
                },
                clock.millis() + 86_400_000L,
            ),
        )
        assertTrue("B 无事件", statusRepo.events.none { it.medicationId == b })
    }

    @Test
    fun `C7 a mixed reconcile writes exactly the changed medications`() = runTest {
        val a = seed("A")
        seed("B")
        val paused = seed("C").also { pause(it) }
        val vm = settled(viewModel())
        val b = vm.state.value.lines.single { it.name == "B" }.id
        vm.onToggle(a)
        vm.onToggle(b)
        vm.onToggle(paused)
        vm.toSummary()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.summary!!.toPause.size)
        assertEquals(1, vm.state.value.summary!!.toResume.size)

        vm.confirm()
        advanceUntilIdle()
        // 只统计本次新写入的事件（预置 PAUSE 的 recordedAtMs 很小）。
        val added = statusRepo.events.filter { it.recordedAtMs > 1_000_000_000L }
        assertEquals("2 PAUSE + 1 RESUME", 3, added.size)
        assertEquals(2, added.count { it.kind == MedicationStatusKind.PAUSE })
        assertEquals(1, added.count { it.kind == MedicationStatusKind.RESUME })
    }

    @Test
    fun `C10 confirming the same day twice is idempotent`() = runTest {
        seed("A")
        val vm = settled(viewModel())
        val a = vm.state.value.lines.single().id
        vm.onToggle(a)
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        val afterFirst = statusRepo.events.size

        vm.backToEdit()
        advanceUntilIdle()
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals("第二次空 diff、零新事件", afterFirst, statusRepo.events.size)
        assertEquals(day.toEpochDay(), prefs.userCheckedDate(1L))
    }

    @Test
    fun `C11 a legacy baseline checked becomes a dated resume without reinterpreting the past`() = runTest {
        val id = seed("遗留")
        val migrationMs = 1_700_000_000_000L
        statusRepo.insertEvent(
            MedicationStatusEvent(
                medicationId = id,
                kind = MedicationStatusKind.LEGACY_BASELINE,
                effectiveAtMs = null,
                recordedAtMs = migrationMs,
            ),
        )
        val vm = settled(viewModel())
        val line = vm.state.value.lines.single()
        assertEquals("遗留药当前态 = PAUSED ⇒ 不预勾选", false, line.checked)
        assertTrue(line.legacyUnknown)

        vm.onToggle(id)
        vm.toSummary()
        advanceUntilIdle()
        assertEquals(listOf(id), vm.state.value.summary!!.toResume)
        vm.confirm()
        advanceUntilIdle()

        val resume = statusRepo.events.single { it.kind == MedicationStatusKind.RESUME }
        assertEquals(localStart(day), resume.effectiveAtMs)
        assertEquals(
            "t<M 仍 UNKNOWN",
            MedicationStatus.UNKNOWN,
            MedicationStatusDerivation.statusAt(
                statusRepo.events,
                migrationMs - 1,
            ),
        )
    }

    @Test
    fun `C16b keeping a legacy medication unchecked writes zero events`() = runTest {
        val id = seed("遗留")
        statusRepo.insertEvent(
            MedicationStatusEvent(
                medicationId = id,
                kind = MedicationStatusKind.LEGACY_BASELINE,
                effectiveAtMs = null,
                recordedAtMs = 1_700_000_000_000L,
            ),
        )
        val vm = settled(viewModel())
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals("不勾 = 零事件（绝不是补一条 PAUSE）", 1, statusRepo.events.size)
        assertEquals(0, statusRepo.events.count { it.kind == MedicationStatusKind.PAUSE })
    }

    @Test
    fun `C12 confirming after midnight does not write the stale day`() = runTest {
        seed("A")
        val vm = settled(viewModel())
        vm.onToggle(vm.state.value.lines.single().id)
        vm.toSummary()
        advanceUntilIdle()
        assertEquals(day, vm.state.value.summary!!.effectiveDay)

        clock.now = Instant.parse("2026-09-19T17:00:00Z") // D+1 01:00 本地
        vm.confirm()
        advanceUntilIdle()

        assertEquals("不落库", 0, statusRepo.events.size)
        assertTrue("摘要重建并标记过期", vm.state.value.stale)
        assertEquals(ReconcileStep.SUMMARY, vm.state.value.step)
        assertEquals("重建为 D+1", day.plusDays(1), vm.state.value.summary!!.effectiveDay)
        assertNull(prefs.userCheckedDate(1L))
    }

    @Test
    fun `C14 all unchecked pauses every active medication after the guard fires`() = runTest {
        seed("A")
        seed("B")
        seed("C")
        val vm = settled(viewModel())
        vm.state.value.lines.forEach { vm.onToggle(it.id) }
        vm.toSummary()
        advanceUntilIdle()

        assertTrue("p == n ⇒ 需二次确认", vm.state.value.summary!!.needsSecondConfirmation)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(3, statusRepo.events.count { it.kind == MedicationStatusKind.PAUSE })
    }

    @Test
    fun `C14 all checked resumes every non active medication`() = runTest {
        val a = seed("A")
        val b = seed("B")
        pause(a)
        pause(b)
        val vm = settled(viewModel())
        vm.state.value.lines.forEach { vm.onToggle(it.id) }
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals(2, statusRepo.events.count { it.kind == MedicationStatusKind.RESUME })
    }

    @Test
    fun `E9 a member switch makes the confirm abort and rebuild for the new member`() = runTest {
        seed("A")
        val vm = settled(viewModel())
        vm.onToggle(vm.state.value.lines.single().id)
        vm.toSummary()
        advanceUntilIdle()

        whenever(activeRecipient.current()).thenReturn(2L)
        vm.confirm()
        advanceUntilIdle()

        assertEquals("回到列表按新成员重建", ReconcileStep.LIST, vm.state.value.step)
        assertTrue(vm.state.value.memberChanged)
        assertEquals("A 名下零事件", 0, statusRepo.events.size)
        assertNull(prefs.userCheckedDate(1L))
    }

    @Test
    fun `C8 an empty reason writes null and E13 a filled reason is shared by the batch`() = runTest {
        val a = seed("A")
        seed("B")
        val vm = settled(viewModel())
        val b = vm.state.value.lines.single { it.name == "B" }.id
        vm.onToggle(a)
        vm.onToggle(b)
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals(2, statusRepo.events.size)
        assertTrue("留空 ⇒ reason == null", statusRepo.events.all { it.reason == null })

        // E13：填写原因 ⇒ 本批共用该原因
        vm.backToEdit()
        advanceUntilIdle()
        vm.onReasonChange("医嘱调整")
        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        // 已停用 ⇒ 再确认无新事件
        assertEquals(2, statusRepo.events.size)
    }

    @Test
    fun `C15 a concurrent external change rebuilds the stale summary instead of committing`() = runTest {
        seed("A")
        seed("B")
        val vm = settled(viewModel())
        val a = vm.state.value.lines.single { it.name == "A" }.id
        vm.onToggle(a)
        vm.toSummary()
        advanceUntilIdle()

        // 摘要后别处把 A 停了（其活 kind 变化）⇒ 事务内校验失败、整体中止、摘要重建
        pause(a)
        vm.confirm()
        advanceUntilIdle()

        assertTrue(vm.state.value.stale)
        assertEquals(ReconcileStep.SUMMARY, vm.state.value.step)
        assertEquals("A 不再出现在 toPause", 0, vm.state.value.summary!!.toPause.count { it == a })
        assertEquals("除注入外零新增", 1, statusRepo.events.size)
        assertNull(prefs.userCheckedDate(1L))
    }
}
