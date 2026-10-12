package com.driezy.medlog.feature.medications.reconcile

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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 非服药 / 非剂量不变量（T3）——docs/medication-daily-reconcile.md §13 / §9 C3 / §10。
 *
 * 本流程**绝不**写 `medication_logs`、**不是**剂次依从证据：它在**结构上**不依赖任何日志 DAO/仓储
 * （下面用构造器签名断言钉死），且空差异确认**零状态事件**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationReconcileInvarianceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun wiringOf(clazz: Class<*>): List<String> = clazz.declaredConstructors
        .flatMap { it.parameterTypes.toList() }
        .map { it.name }

    private fun assertNoLogDependency(clazz: Class<*>) {
        val logLike = wiringOf(clazz).filter {
            it.contains("MedicationLog") || it.contains("LogDao") || it.contains("LogRepository")
        }
        assertTrue("$clazz 不得依赖任何日志写入接口（本流程不记录服药）: $logLike", logLike.isEmpty())
    }

    @Test
    fun `the reconcile view model never wires a medication log dependency`() {
        assertNoLogDependency(MedicationDailyReconcileViewModel::class.java)
    }

    @Test
    fun `the status write use case never wires a medication log dependency`() {
        assertNoLogDependency(SetMedicationStatusUseCase::class.java)
    }

    @Test
    fun `the reconcile view model depends only on the status side plus the clock`() {
        val names = wiringOf(MedicationDailyReconcileViewModel::class.java).toSet()
        assertEquals(
            setOf(
                "com.driezy.medlog.data.repository.MedicationRepository",
                "com.driezy.medlog.data.repository.MedicationStatusRepository",
                "com.driezy.medlog.feature.medications.application.SetMedicationStatusUseCase",
                "com.driezy.medlog.data.repository.UserPreferencesRepository",
                "com.driezy.medlog.data.recipient.ActiveRecipientStore",
                "java.time.Clock",
            ),
            names,
        )
    }

    @Test
    fun `C3 an empty diff confirm writes zero status events`() = runTest {
        val medications = FakeMedicationRepository()
        val statusRepo = FakeMedicationStatusRepository()
        val prefs = FakeMedicationStatusPreferences()
        val activeRecipient = mock<ActiveRecipientStore>()
        whenever(activeRecipient.current()).thenReturn(1L)
        val userPreferences = mock<UserPreferencesRepository>()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "A", doseUnit = "片"))
        val useCase = SetMedicationStatusUseCase(
            medications,
            statusRepo,
            prefs,
            userPreferences,
            activeRecipient,
            object : TransactionRunner {
                override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
            },
            mock<ReconcileRemindersUseCase>(),
            Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone),
        )
        val vm =
            MedicationDailyReconcileViewModel(
                medications,
                statusRepo,
                useCase,
                userPreferences,
                activeRecipient,
                Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone),
            )
        advanceUntilIdle()

        vm.toSummary()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()

        assertEquals("空差异确认零状态事件", 0, statusRepo.events.size)
    }
}
