package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.local.MedicationDao
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.model.PlanWriteResult
import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.MedicationRepositoryImpl
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.ReminderReconciler
import com.driezy.medlog.domain.ReminderReconciliationQueue
import com.driezy.medlog.domain.model.MedicationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * B3 启动重算（docs/medication-time-offset.md §3.6）的 JVM 行为钉：
 *
 * - **不阻塞**：`schedule()` 返回时重算还没跑（StandardTestDispatcher 上必须 `advanceUntilIdle` 才发生），
 *   所以它不可能内部 `runBlocking` / `await`；
 * - **走 B2 的同一条写路径**：这里装配的是真实的 [MedicationRepositoryImpl] + 真实的
 *   [ResyncRemindersUseCase] + 真实的 `planRoutineBatch`，只有 DAO/事务/DataStore 是假的；
 * - **失败分类 + 持久标记**：非法偏移 / 边界拒绝 / 其它异常各自记录，且 `reconcile` 不跑；
 * - **幂等**：重复启动与重复重算零额外修订、零额外标记写；
 * - **中断安全**：批中取消 ⇒ 整批回滚、无标记；下一次启动收敛且不重复建修订。
 */
class RoutineStartupConvergenceTest {

    @Test
    fun `no recipient means no routine read, no write and no reconcile`() = runTest {
        val harness = harness(recipientId = ActiveRecipientStore.NO_RECIPIENT)

        val record = harness.convergence.convergeNow()

        verify(harness.preferences, never()).routineScheduleFor(any())
        verify(harness.dao, never()).getAllMedicationsOnce(any())
        assertEquals("没有成员 ⇒ 一次事务都不该开", 0, harness.store.transactions)
        assertEquals(0, harness.store.updateCalls)
        assertTrue("没有可重排的东西", harness.reconciler.allReasons.isEmpty())
        assertTrue("也不该入队", harness.queue.reasons.isEmpty())
        assertEquals(StartupRecomputeStatus.SKIPPED_NO_RECIPIENT, record.status)
        assertEquals(StartupRecomputeStatus.SKIPPED_NO_RECIPIENT, harness.marker.record?.status)
        assertNull(harness.marker.record?.failure)
    }

    @Test
    fun `an empty medication list is a successful commit and still reconciles`() = runTest {
        val harness = harness(medications = emptyList())

        val record = harness.convergence.convergeNow()

        assertEquals("空清单也要真的读一次（判定集合 ≡ 写入集合）", 1, harness.store.snapshots)
        assertEquals(0, harness.store.updateCalls)
        assertEquals(0, harness.store.revisions)
        assertEquals("提交成功 ⇒ reconcile 照常跑（既有语义：无条件、幂等）", 1, harness.reconciler.allReasons.size)
        assertEquals(StartupRecomputeStatus.CONVERGED, record.status)
        assertEquals(0, record.advanced)
        assertEquals(0, record.noOp)
    }

    @Test
    fun `a normal startup recompute advances every changed medication and reconciles once`() = runTest {
        val harness = harness()

        val record = harness.convergence.convergeNow()

        assertEquals(2, record.advanced)
        assertEquals(0, record.noOp)
        assertEquals("每味变了的药恰一次 updatePlan ⇒ 恰一条修订", 2, harness.store.revisions)
        assertEquals("afterBreakfast/afterDinner = 锚点 + 15min", listOf("09:15", "19:15"), harness.store.timesOf(1L, 2L))
        assertEquals(listOf(ReminderReconcileReason.ROUTINE_CHANGED), harness.reconciler.allReasons)
        assertEquals(listOf(ReminderReconcileReason.ROUTINE_CHANGED), harness.queue.reasons)
        assertEquals(StartupRecomputeStatus.CONVERGED, harness.marker.record?.status)
        assertEquals(2, harness.marker.record?.advanced ?: -1)
    }

    @Test
    fun `an illegal offset is classified, recorded and reminders are not reconciled`() = runTest {
        val harness = harness(
            medications = listOf(
                medication(id = 1L, timePeriod = "afterBreakfast", time = "08:15"),
                medication(id = 2L, timePeriod = "bedtime", time = "22:30", offsetMinutes = 30),
            ),
            schedule = RoutineSchedule(breakfast = RoutineTime(9, 0), bed = RoutineTime(23, 30)),
        )

        val record = harness.convergence.convergeNow()

        assertEquals(StartupRecomputeStatus.FAILED, record.status)
        assertEquals(StartupRecomputeFailure.ILLEGAL_OFFSET, record.failure)
        assertEquals("冲突必须具名到那一味药", 1, record.conflicts.size)
        assertTrue("冲突行要带药名与结果钟点：${record.conflicts}", record.conflicts.single().contains("name=Medication 2"))
        assertTrue("冲突行要带结果分钟：${record.conflicts}", record.conflicts.single().contains("minuteOfDay=1440"))
        assertTrue("抛出前合法药确实被写过（收集而非早退）", harness.store.attempted.contains(1L))
        assertEquals("整批回滚：一条修订都不落", 0, harness.store.revisions)
        assertEquals("库里的钟点逐字不变", listOf("08:15", "22:30"), harness.store.timesOf(1L, 2L))
        assertTrue("失败时 reconcile 根本不会跑", harness.reconciler.allReasons.isEmpty())
        assertTrue("也不该入队", harness.queue.reasons.isEmpty())
        assertEquals(StartupRecomputeFailure.ILLEGAL_OFFSET, harness.marker.record?.failure)
        assertEquals(1, harness.marker.record?.attempts)
    }

    @Test
    fun `a plan boundary rejection is classified and recorded`() = runTest {
        val harness = harness()
        harness.store.updateBehavior = { medication ->
            if (medication.id == 2L) {
                throw PlanBoundaryNotAdvancedException(
                    medicationId = 2L,
                    boundaryMs = TickingClock.START,
                    requestedMs = TickingClock.START,
                )
            }
        }

        val record = harness.convergence.convergeNow()

        assertEquals(StartupRecomputeStatus.FAILED, record.status)
        assertEquals(StartupRecomputeFailure.PLAN_BOUNDARY, record.failure)
        assertTrue(
            "detail 要带异常类名：${record.detail}",
            record.detail?.startsWith("PlanBoundaryNotAdvancedException") == true,
        )
        assertEquals(0, harness.store.revisions)
        assertTrue(harness.reconciler.allReasons.isEmpty())
        assertTrue(harness.queue.reasons.isEmpty())
    }

    @Test
    fun `an unexpected exception is classified as other, recorded, and does not escape`() = runTest {
        val harness = harness()
        harness.store.updateBehavior = { throw IOException("disk full") }

        val outcome = runCatching { harness.convergence.convergeNow() }

        assertTrue("意外异常不得穿出（onCreate 不等它，也不能被它拖垮）", outcome.isSuccess)
        val record = harness.marker.record!!
        assertEquals(StartupRecomputeStatus.FAILED, record.status)
        assertEquals(StartupRecomputeFailure.OTHER, record.failure)
        assertTrue("detail 要带异常类名：${record.detail}", record.detail?.startsWith("IOException: disk full") == true)
        assertEquals(0, harness.store.revisions)
        assertTrue(harness.reconciler.allReasons.isEmpty())
    }

    @Test
    fun `consecutive failures increment the persisted attempt counter`() = runTest {
        val harness = harness()
        harness.store.updateBehavior = { throw IOException("disk full") }

        harness.convergence.convergeNow()
        val second = harness.convergence.convergeNow()

        assertEquals(1, harness.marker.firstRecord?.attempts ?: -1)
        assertEquals(2, second.attempts)
        assertEquals("每次失败都要落记（attempts 变了就是真的变了）", 2, harness.marker.writes)
    }

    @Test
    fun `repeated recomputes converge with zero extra writes and no extra marker write`() = runTest {
        val harness = harness()

        val first = harness.convergence.convergeNow()
        val second = harness.convergence.convergeNow()
        assertEquals(2, first.advanced)
        assertEquals(0, second.advanced)
        assertEquals("第二次全部走「未变」分支", 2, second.noOp)
        assertEquals("重复一次不得多写一行、多建一条修订", 2, harness.store.revisions)
        assertEquals("两次启动两次事务（第二次是零写事务）", 2, harness.store.transactions)
        assertEquals("稳态不重写标记（边沿触发）", 1, harness.marker.writes)
    }

    @Test
    fun `the startup trigger returns before the recompute runs`() = runTest {
        val harness = harness()
        try {
            harness.convergence.schedule()

            assertEquals("schedule() 返回时重算必须还没跑：内部不得 runBlocking / await", 0, harness.store.snapshots)
            assertTrue(harness.reconciler.allReasons.isEmpty())
            assertNull(harness.marker.record)

            advanceUntilIdle()

            assertEquals(1, harness.store.snapshots)
            assertEquals(StartupRecomputeStatus.CONVERGED, harness.marker.record?.status)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `two startup triggers launched together serialize and still write once`() = runTest {
        val harness = harness()
        try {
            harness.convergence.schedule()
            harness.convergence.schedule()
            advanceUntilIdle()

            assertEquals("两次启动各开一个事务", 2, harness.store.transactions)
            assertEquals("只有第一次真的写", 2, harness.store.revisions)
            assertEquals(1, harness.marker.writes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the supervisor scope survives a failed startup recompute`() = runTest {
        val harness = harness()
        try {
            harness.store.updateBehavior = { throw IOException("disk full") }

            harness.convergence.schedule()
            advanceUntilIdle()

            assertEquals(StartupRecomputeStatus.FAILED, harness.marker.record?.status)
            assertTrue("失败不得取消 application 作用域", harness.scope.isActive)

            harness.store.updateBehavior = {}
            harness.convergence.schedule()
            advanceUntilIdle()

            assertEquals("作用域还活着 ⇒ 下一次启动照常收敛", StartupRecomputeStatus.CONVERGED, harness.marker.record?.status)
            assertEquals(2, harness.marker.record?.advanced ?: -1)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a kill in the middle of the batch rolls back and the next start converges`() = runTest {
        val harness = harness()
        try {
            harness.store.updateBehavior = { medication ->
                if (medication.id == 2L) throw CancellationException("process killed mid-batch")
            }

            harness.convergence.schedule()
            advanceUntilIdle()

            assertEquals("被杀的那一批：一行都不落", 0, harness.store.revisions)
            assertEquals(listOf("08:15", "18:15"), harness.store.timesOf(1L, 2L))
            assertNull("取消不写标记：库里的状态就是下一次启动的输入", harness.marker.record)
            assertTrue("也不得重排", harness.reconciler.allReasons.isEmpty())
            assertTrue(harness.scope.isActive)

            // "下一次启动"：同一份库、健康的写路径。
            harness.store.updateBehavior = {}
            harness.convergence.schedule()
            advanceUntilIdle()

            assertEquals(listOf("09:15", "19:15"), harness.store.timesOf(1L, 2L))
            assertEquals("恰好两味各一条修订，不重复", 2, harness.store.revisions)
            assertEquals(StartupRecomputeStatus.CONVERGED, harness.marker.record?.status)
            assertEquals(2, harness.marker.record?.advanced ?: -1)
            assertEquals("被杀那次没重排 ⇒ 只有恢复后的这一次", 1, harness.reconciler.allReasons.size)
        } finally {
            harness.close()
        }
    }

    // ── NEGATIVE CONTROL ──────────────────────────────────────────────────────
    // 下面三条用**错误形态**跑同一批可观测量，断言"生产用例里那条断言会因此变红"。
    // 也就是说：把 B3 的行为换回"等待 / 吞掉 / 失败后照常重排"，这些控制先抓住它。

    @Test
    fun `control - starting with an await would fail the non-blocking assertion`() = runTest {
        val harness = harness()

        // 反例：在启动调用点等待结果（= await / runBlocking 的实际效果）
        harness.convergence.convergeNow()

        assertNotEquals(
            "控制有效性：等待式启动必须让「schedule() 返回后 snapshots == 0」的断言失败",
            0,
            harness.store.snapshots,
        )
    }

    @Test
    fun `control - swallowing a failure would fail the recording assertion`() = runTest {
        val harness = harness()
        harness.store.updateBehavior = { throw IOException("disk full") }

        // 反例：调了写路径、吞掉异常、不留任何记录（静默失败形态）
        runCatching { harness.resync(MOVED_ROUTINE) }

        assertNull(
            "控制有效性：吞掉失败时必须让「marker.record 是 FAILED」的断言失败（这里 record 为 null）",
            harness.marker.record,
        )
        assertTrue("而且没人重排", harness.reconciler.allReasons.isEmpty())
    }

    @Test
    fun `control - reconciling after a failed batch would fail the no-reconcile assertion`() = runTest {
        val harness = harness()
        harness.store.updateBehavior = { throw IOException("disk full") }

        // 反例：失败之后仍然重排（"拿未提交的结果去重排"形态）
        runCatching { harness.repository.planForRoutine(MOVED_ROUTINE) }
        harness.reconcile.all(ReminderReconcileReason.ROUTINE_CHANGED)

        assertNotEquals(
            "控制有效性：失败后重排必须让「reconciler.allReasons.isEmpty()」的断言失败",
            0,
            harness.reconciler.allReasons.size,
        )
    }

    @Test
    fun `the trigger holds no blocking wait and names its failures`() {
        val source = File("src/main/java/com/driezy/medlog/$CONVERGENCE_SOURCE")
            .takeIf(File::exists)
            ?: repoFile("app/src/main/java/com/driezy/medlog/$CONVERGENCE_SOURCE")
        val code = source.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse("启动路径不得 runBlocking", "runBlocking" in code)
        assertFalse("启动路径不得 await", ".await(" in code)
        assertFalse("启动路径不得 join", ".join(" in code)
        assertTrue("必须从作用域 launch", "scope.launch" in code)
        assertTrue("稳定日志标签", "TAG = \"RoutineStartupConvergence\"" in code)
        assertTrue(StartupRecomputeFailure.ILLEGAL_OFFSET.name in code)
        assertTrue(StartupRecomputeFailure.PLAN_BOUNDARY.name in code)
        assertTrue(StartupRecomputeFailure.OTHER.name in code)
        assertTrue("只走 B2 的用例，不得自己拼写路径", "resyncReminders(" in code)
    }

    private suspend fun TestScope.harness(
        recipientId: Long = RECIPIENT,
        medications: List<Medication> = DEFAULT_MEDICATIONS,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        schedule: RoutineSchedule = MOVED_ROUTINE,
    ): Harness {
        val store = PlanStore(medications)
        val dao: MedicationDao = mock()
        whenever(dao.getAllMedicationsOnce(recipientId)).thenAnswer { store.snapshot() }
        whenever(dao.updatePlan(any(), any())).thenAnswer { store.onUpdate(it.getArgument(0)) }
        val activeRecipient: ActiveRecipientStore = mock()
        whenever(activeRecipient.current()).thenReturn(recipientId)
        val preferences: UserPreferencesRepository = mock()
        whenever(preferences.routineScheduleFor(recipientId)).thenReturn(schedule)
        val repository = MedicationRepositoryImpl(dao, store, activeRecipient, TickingClock(), mock())
        val reconciler = RecordingReconciler()
        val queue = RecordingQueue()
        val reconcile = ReconcileRemindersUseCase(reconciler, queue)
        val resync = ResyncRemindersUseCase(repository, reconcile)
        val marker = FakeStartupMarker()
        val convergence = RoutineStartupConvergence(activeRecipient, preferences, resync, marker, TickingClock(), scope)
        return Harness(
            convergence = convergence,
            repository = repository,
            resync = resync,
            reconcile = reconcile,
            store = store,
            dao = dao,
            preferences = preferences,
            reconciler = reconciler,
            queue = queue,
            marker = marker,
            scope = scope,
        )
    }

    private fun repoFile(relative: String): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("找不到仓库根目录（settings.gradle.kts）")
        return File(root, relative)
    }

    private data class Harness(
        val convergence: RoutineStartupConvergence,
        val repository: MedicationRepositoryImpl,
        val resync: ResyncRemindersUseCase,
        val reconcile: ReconcileRemindersUseCase,
        val store: PlanStore,
        val dao: MedicationDao,
        val preferences: UserPreferencesRepository,
        val reconciler: RecordingReconciler,
        val queue: RecordingQueue,
        val marker: FakeStartupMarker,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    /**
     * 假的"库 + 事务"：`updatePlan` 先进 pending，事务正常返回才落地（= 提交），
     * 抛异常整批丢弃（= Room 回滚）。`snapshot()` 读的是**已提交**的行，因此第二次重算看到的是新值。
     */
    private class PlanStore(initial: List<Medication>) : TransactionRunner {
        private val rows = LinkedHashMap<Long, Medication>().apply { initial.forEach { put(it.id, it) } }
        private val pending = mutableListOf<Medication>()

        var snapshots = 0
            private set
        var updateCalls = 0
            private set
        var revisions = 0
            private set
        var transactions = 0
            private set
        val attempted = mutableListOf<Long>()

        /** 测试钩子：在 `updatePlan` 内抛异常（模拟边界拒绝 / 磁盘错误 / 批中被杀）。 */
        var updateBehavior: (Medication) -> Unit = {}

        fun snapshot(): List<Medication> {
            snapshots++
            return rows.values.toList()
        }

        fun onUpdate(medication: Medication): PlanWriteResult {
            updateCalls++
            attempted += medication.id
            updateBehavior(medication)
            pending += medication
            return PlanWriteResult.Advanced
        }

        fun timesOf(vararg ids: Long): List<String> = ids.map { rows.getValue(it).reminderTimes }

        override suspend fun <R> withTransaction(block: suspend () -> R): R {
            transactions++
            return try {
                val result = block()
                pending.forEach { rows[it.id] = it }
                revisions += pending.size
                pending.clear()
                result
            } catch (error: Throwable) {
                pending.clear()
                throw error
            }
        }
    }

    private class FakeStartupMarker : StartupRecomputeMarker {
        private val current = MutableStateFlow<StartupRecomputeRecord?>(null)
        override val state: StateFlow<StartupRecomputeRecord?> = current

        var writes = 0
            private set
        var firstRecord: StartupRecomputeRecord? = null
            private set

        val record: StartupRecomputeRecord? get() = current.value

        override suspend fun latest(): StartupRecomputeRecord? = current.value

        override suspend fun write(record: StartupRecomputeRecord) {
            writes++
            if (firstRecord == null) firstRecord = record
            current.value = record
        }
    }

    private class RecordingReconciler : ReminderReconciler {
        val allReasons = mutableListOf<ReminderReconcileReason>()

        override suspend fun reconcileMedication(id: MedicationId, reason: ReminderReconcileReason) = Unit

        override suspend fun reconcileCareTask(id: Long, reason: ReminderReconcileReason) = Unit

        override suspend fun reconcileAll(reason: ReminderReconcileReason) {
            allReasons += reason
        }
    }

    private class RecordingQueue : ReminderReconciliationQueue {
        val reasons = mutableListOf<ReminderReconcileReason>()

        override fun enqueue(reason: ReminderReconcileReason) {
            reasons += reason
        }
    }

    private class TickingClock : Clock() {
        private var now = START

        override fun millis(): Long = now++

        override fun instant(): Instant = Instant.ofEpochMilli(millis())

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        companion object {
            const val START = 1_700_000_000_000L
        }
    }

    private companion object {
        const val RECIPIENT = 7L
    }
}

private const val CONVERGENCE_SOURCE =
    "capability/reminders/application/RoutineStartupConvergence.kt"

private val MOVED_ROUTINE = RoutineSchedule(breakfast = RoutineTime(9, 0), dinner = RoutineTime(19, 0))

private val DEFAULT_MEDICATIONS = listOf(
    medication(id = 1L, timePeriod = "afterBreakfast", time = "08:15"),
    medication(id = 2L, timePeriod = "afterDinner", time = "18:15"),
)

private fun medication(id: Long, timePeriod: String, time: String, offsetMinutes: Int = 0) = Medication(
    id = id,
    name = "Medication $id",
    doseUnit = "tablet",
    timePeriod = timePeriod,
    reminderTimes = time,
    reminderHour = time.substringBefore(':').toInt(),
    reminderMinute = time.substringAfter(':').toInt(),
    offsetMinutes = offsetMinutes,
    careRecipientId = 7L,
)
