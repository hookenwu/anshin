package com.driezy.medlog.feature.medications.list

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.FakeMedicationStatusPreferences
import com.driezy.medlog.data.repository.FakeMedicationStatusRepository
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase
import com.driezy.medlog.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * `我的药品`「今天尚未核对」提示（T6）——docs/medication-daily-reconcile.md 裁决 1 / §2 D5 / §9 C1。
 *
 * 关键：**无变更的确认仍记录「今天已核对」**，而它**零状态事件**、只写 DataStore 标记。
 * 提示若只观察状态事件会漏掉这次确认、一直停在「今天尚未核对」；本测试锁定「标记流」这一触发源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyMedicationsStatusHintTest {

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
    private lateinit var statusPrefs: FakeMedicationStatusPreferences
    private lateinit var clock: MutableClock
    private lateinit var activeRecipient: ActiveRecipientStore
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var setStatus: SetMedicationStatusUseCase

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        statusPrefs = FakeMedicationStatusPreferences()
        clock = MutableClock(Instant.parse("2026-09-19T06:00:00Z"), zone) // 本地 14:00
        activeRecipient = mock()
        whenever(activeRecipient.current()).thenReturn(1L)
        whenever(activeRecipient.recipientId).thenReturn(flowOf(1L))
        userPreferences = mock()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        setStatus = SetMedicationStatusUseCase(
            medications,
            statusRepo,
            statusPrefs,
            userPreferences,
            activeRecipient,
            transactionRunner,
            mock<ReconcileRemindersUseCase>(),
            clock,
        )
    }

    private fun viewModel() = MyMedicationsViewModel(
        medications,
        userPreferences,
        statusRepo,
        statusPrefs,
        setStatus,
        activeRecipient,
        clock,
    )

    private suspend fun seed(name: String): Long {
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = name, doseUnit = "片"))
        return medications.getAllMedicationsFor(1L).last().id
    }

    @Test
    fun `an unchanged confirmation clears the pending hint`() = runTest {
        seed("A")
        val vm = viewModel()
        backgroundScope.launch { vm.checkedInToday.collect {} } // WhileSubscribed ⇒ 需有订阅者
        advanceUntilIdle()
        assertFalse("核对前：提示显示「今天尚未核对」", vm.checkedInToday.value)

        // 无变化确认（裁决 1 / D5）：零状态事件，只在提交后写「今天已核对」标记。
        setStatus.markUserChecked()
        advanceUntilIdle()

        assertEquals("零状态事件", 0, statusRepo.events.size)
        assertEquals(day.toEpochDay(), statusPrefs.userCheckedDate(1L))
        assertTrue("确认后提示清除：提示必须观察核对日期标记流", vm.checkedInToday.value)
    }
}
