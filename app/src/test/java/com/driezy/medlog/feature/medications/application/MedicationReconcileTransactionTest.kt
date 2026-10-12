package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.FakeMedicationRepository
import com.driezy.medlog.data.repository.FakeMedicationStatusRepository
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.data.repository.MedicationStatusPreferences
import com.driezy.medlog.data.repository.SettingsPreferences
import com.driezy.medlog.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单事务全落 / 全不落 + 事务内校验 + post-commit 独立处理（T2/T7）——
 * docs/medication-daily-reconcile.md §4.3 / §4.5（A1–A5）/ §6 / §7 E9/E15。
 */
class MedicationReconcileTransactionTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 19)

    private class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private class RecordingPreferences : MedicationStatusPreferences {
        var userChecked: Long? = null
        var setCalls = 0
        var setCallsInsideTransaction = 0
        var throwOnSet = false
        var inTransaction = false

        override suspend fun autoReconciledAtMs(recipientId: Long): Long? = null
        override suspend fun setAutoReconciledAtMs(recipientId: Long, atMs: Long) = Unit
        override suspend fun userCheckedDate(recipientId: Long): Long? = userChecked
        override fun userCheckedDateFlow(recipientId: Long): Flow<Long?> = flowOf(userChecked)
        override suspend fun clearForRecipient(recipientId: Long) {
            userChecked = null
        }

        override suspend fun setUserCheckedDate(recipientId: Long, epochDay: Long) {
            setCalls++
            if (inTransaction) setCallsInsideTransaction++
            if (throwOnSet) error("marker write failed")
            userChecked = epochDay
        }
    }

    /** 假事务运行器：计数 + 失败时把事件/药品快照恢复（模拟 Room 回滚），并跟踪「事务内」。 */
    private class RollbackRunner(
        private val statusRepo: FakeMedicationStatusRepository,
        private val medications: FakeMedicationRepository,
        private val prefs: RecordingPreferences,
    ) : TransactionRunner {
        var calls = 0
        var committed = 0
        var rolledBack = 0
        var onAfterBlock: () -> Unit = {}

        override suspend fun <R> withTransaction(block: suspend () -> R): R {
            calls++
            val eventSnapshot = statusRepo.snapshot()
            val medSnapshot = medications.snapshotAll()
            prefs.inTransaction = true
            return try {
                val result = block()
                committed++
                onAfterBlock()
                result
            } catch (t: Throwable) {
                statusRepo.restore(eventSnapshot)
                medications.restoreAll(medSnapshot)
                rolledBack++
                throw t
            } finally {
                prefs.inTransaction = false
            }
        }
    }

    private class ThrowingMedicationsRepository(
        private val delegate: MedicationRepository,
        private val failCacheFor: Long,
    ) : MedicationRepository by delegate {
        override suspend fun setArchivedCache(id: Long, archived: Boolean) {
            if (id == failCacheFor) error("cache write failed")
            delegate.setArchivedCache(id, archived)
        }
    }

    private lateinit var medications: FakeMedicationRepository
    private lateinit var statusRepo: FakeMedicationStatusRepository
    private lateinit var prefs: RecordingPreferences
    private lateinit var runner: RollbackRunner
    private lateinit var clock: MutableClock
    private lateinit var activeRecipient: ActiveRecipientStore
    private lateinit var reconcile: ReconcileRemindersUseCase
    private lateinit var userPreferences: UserPreferencesRepository

    /** 摘要所见：药1 活跃(null)、药2 活跃(null)、药3 已暂停(PAUSE)。 */
    private val expected: Map<Long, String?> = mapOf(1L to null, 2L to null, 3L to MedicationStatusKind.PAUSE)

    private fun localStart(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun useCase(medicationRepo: MedicationRepository = medications) = SetMedicationStatusUseCase(
        medicationRepo,
        statusRepo,
        prefs,
        userPreferences,
        activeRecipient,
        runner,
        reconcile,
        clock,
    )

    @Before
    fun setUp() = runTest {
        medications = FakeMedicationRepository()
        statusRepo = FakeMedicationStatusRepository()
        prefs = RecordingPreferences()
        clock = MutableClock(Instant.parse("2026-09-19T06:00:00Z"), zone) // 14:00 本地
        runner = RollbackRunner(statusRepo, medications, prefs)
        activeRecipient = mock()
        whenever(activeRecipient.current()).thenReturn(1L)
        reconcile = mock()
        userPreferences = mock()
        whenever(userPreferences.settingsFlow).thenReturn(flowOf(SettingsPreferences()))
        repeat(3) { medications.addMedication(Medication(id = 0, careRecipientId = 1L, name = "药$it", doseUnit = "片")) }
        // 药3 已暂停（摘要所见 kind = PAUSE）
        statusRepo.insertEvent(
            MedicationStatusEvent(
                medicationId = 3,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = localStart(day),
                recordedAtMs = 1,
            ),
        )
    }

    @Test
    fun `mixed pause and resume commit in exactly one room transaction`() = runTest {
        val results = useCase().applyReconcile(listOf(1L, 2L), listOf(3L), day, null, expected, 1L)

        assertEquals("混合 kind 必须只开一个事务", 1, runner.calls)
        assertEquals(1, runner.committed)
        assertEquals(0, runner.rolledBack)
        assertEquals("1 预置 + 3 变更", 4, statusRepo.events.size)
        assertTrue(medications.getMedicationById(1L)!!.isArchived)
        assertTrue(medications.getMedicationById(2L)!!.isArchived)
        assertFalse("恢复后缓存翻回活跃", medications.getMedicationById(3L)!!.isArchived)
        assertEquals(3, results.size)
    }

    @Test
    fun `a failing write rolls back every event and cache`() = runTest {
        val uc = useCase(ThrowingMedicationsRepository(medications, failCacheFor = 2L))
        val thrown = runCatching {
            uc.applyReconcile(listOf(1L, 2L), listOf(3L), day, null, expected, 1L)
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertEquals("事件与缓存全不落", 1, runner.rolledBack)
        assertEquals("只剩药3 的预置事件", 1, statusRepo.events.size)
        assertFalse("药1 缓存回滚", medications.getMedicationById(1L)!!.isArchived)
        assertFalse("药2 缓存回滚", medications.getMedicationById(2L)!!.isArchived)
        assertNull("事务抛错 ⇒ ③ 未执行 ⇒ 标记未写", prefs.userChecked)
        verify(reconcile, never()).medication(any(), any())
    }

    @Test
    fun `C20 a concurrent change aborts the transaction and never leads the marker`() = runTest {
        // 摘要后别处把药1（原本 live kind = null）停用 ⇒ 活 kind 变 ⇒ 事务内校验失败。
        statusRepo.insertEvent(
            MedicationStatusEvent(
                1L,
                medicationId = 1,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = localStart(day),
                recordedAtMs = 10,
            ),
        )
        val thrown = runCatching {
            useCase().applyReconcile(listOf(1L, 2L), listOf(3L), day, null, expected, 1L)
        }.exceptionOrNull()

        assertTrue(thrown is ReconcileAbortedException)
        assertEquals(1, runner.rolledBack)
        assertEquals("零新增事件（含预置 2 条）", 2, statusRepo.events.size)
        assertNull("标记绝不领先（A1/A5）", prefs.userChecked)
    }

    @Test
    fun `an unchanged displayed medication changed concurrently still aborts`() = runTest {
        // 药2 在摘要里显示为「未变化」；摘要后别处把它停了 ⇒ 校验必须覆盖它（细化 2）。
        statusRepo.insertEvent(
            MedicationStatusEvent(
                1L,
                medicationId = 2,
                kind = MedicationStatusKind.PAUSE,
                effectiveAtMs = localStart(day),
                recordedAtMs = 11,
            ),
        )
        val thrown = runCatching {
            useCase().applyReconcile(listOf(1L), emptyList(), day, null, expected, 1L)
        }.exceptionOrNull()

        assertTrue("显示为未变化的药变更也必须中止", thrown is ReconcileAbortedException)
        assertEquals(0, runner.committed)
        assertNull(prefs.userChecked)
    }

    @Test
    fun `E9 a member switch in the transaction aborts`() = runTest {
        whenever(activeRecipient.current()).thenReturn(2L)
        val thrown = runCatching {
            useCase().applyReconcile(listOf(1L), emptyList(), day, null, expected, expectedRecipientId = 1L)
        }.exceptionOrNull()

        assertTrue(thrown is ReconcileAbortedException)
        assertEquals(0, runner.committed)
        assertNull(prefs.userChecked)
    }

    @Test
    fun `C18 a failing marker write keeps the status committed and the hint pending`() = runTest {
        prefs.throwOnSet = true
        val results = useCase().applyReconcile(listOf(1L, 2L), listOf(3L), day, null, expected, 1L)

        assertEquals(1, runner.committed)
        assertEquals(4, statusRepo.events.size)
        assertTrue(medications.getMedicationById(1L)!!.isArchived)
        assertNull("标记未写（弱事实滞后，绝不回滚状态）", prefs.userChecked)
        assertEquals("标记绝不在事务内写", 0, prefs.setCallsInsideTransaction)
        verify(reconcile, times(3)).medication(any(), any()) // A4：③ 失败不阻断 ④
        assertEquals(3, results.size)
    }

    @Test
    fun `C19 re-running after a marker failure writes no duplicate events and advances the marker`() = runTest {
        prefs.throwOnSet = true
        useCase().applyReconcile(listOf(1L, 2L), listOf(3L), day, null, expected, 1L)
        val afterFirst = statusRepo.events.size

        prefs.throwOnSet = false
        // 重跑：现活 kind == 目标 ⇒ 全 CHECK（零新事件）；ALWAYS ⇒ 标记前进（自愈）。
        val nowExpected = mapOf(
            1L to MedicationStatusKind.PAUSE,
            2L to MedicationStatusKind.PAUSE,
            3L to MedicationStatusKind.RESUME,
        )
        useCase().applyReconcile(listOf(1L, 2L), listOf(3L), day, null, nowExpected, 1L)

        assertEquals("零重复事件（幂等）", afterFirst, statusRepo.events.size)
        assertEquals(day.toEpochDay(), prefs.userChecked)
    }

    @Test
    fun `A4 a failing reconcile does not affect the committed state or the marker`() = runTest {
        whenever(reconcile.medication(any(), any())).thenThrow(RuntimeException("reconcile boom"))
        val results = useCase().applyReconcile(listOf(1L), emptyList(), day, null, expected, 1L)

        assertEquals(1, runner.committed)
        assertTrue(medications.getMedicationById(1L)!!.isArchived)
        assertEquals("④ 失败不影响已写标记", day.toEpochDay(), prefs.userChecked)
        assertEquals(1, results.size)
    }

    @Test
    fun `reconcile runs only after the transaction commits`() = runTest {
        val committedAtReconcile = mutableListOf<Int>()
        whenever(reconcile.medication(any(), any())).thenAnswer {
            committedAtReconcile += runner.committed
            Unit
        }
        useCase().applyReconcile(listOf(1L), emptyList(), day, null, emptyMap(), 1L)

        assertTrue(committedAtReconcile.isNotEmpty())
        assertTrue("reconcile 必须在提交之后触发", committedAtReconcile.all { it == 1 })
        assertEquals(0, prefs.setCallsInsideTransaction)
    }

    @Test
    fun `C21 the marker is stamped with the transaction validated day - not a re-read today`() = runTest {
        // 提交成功后、写标记之前把时钟拨过零点 ⇒ 标记必须钉在事务校验过的 day（D），不是 D+1。
        runner.onAfterBlock = { clock.now = Instant.parse("2026-09-19T17:00:00Z") } // D+1 01:00 本地
        useCase().applyReconcile(emptyList(), emptyList(), day, null, expected, 1L)

        assertEquals("标记日 = 事务验证过的 summaryDay", day.toEpochDay(), prefs.userChecked)
    }

    @Test
    fun `the marker is never written inside the room transaction`() = runTest {
        useCase().applyReconcile(listOf(1L), emptyList(), day, null, expected, 1L)

        assertEquals(0, prefs.setCallsInsideTransaction)
        assertEquals(1, prefs.setCalls)
    }
}
