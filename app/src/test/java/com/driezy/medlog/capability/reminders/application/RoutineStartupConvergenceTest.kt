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
        assertEquals("零变化那一次也必须重排（无条件、幂等）", 2, harness.reconciler.allReasons.size)
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

    // ── W2：提交之后、reconcile 之前的窗口 ────────────────────────────────────

    /**
     * W2（§3.7）的端到端钉：**第一次启动把新钟点提交进库、在 reconcile 之前进程死**；
     * 第二次启动时库已经是最新 ⇒ 重算必然是 NoOp ⇒ **NoOp 也必须真的重排**。
     *
     * 终止是**确定注入**的，不是碰运气：切点在 [PlanStore] 的 `afterCommit`
     * ——`withTransaction` 已经把 pending 行落进 `rows`（= Room 提交成功）、控制权回到
     * `ResyncRemindersUseCase` 之前那一刻。此处抛 `CancellationException` 与真实进程被杀同义：
     * 事务已提交、`reconcileReminders.all(...)` 那一行永远不会被执行，连入队都没有机会跑。
     */
    @Test
    fun `a start killed after the commit and before the reconcile still reconciles on the next no-op start`() =
        runTest {
            val first = harness()
            try {
                first.store.afterCommit = {
                    throw CancellationException("process killed right after commit, before reconcile")
                }

                first.convergence.schedule()
                advanceUntilIdle()

                // 第一次启动的落库状态 = W2 的输入：钟点已新、闹钟仍旧、没人重排过。
                assertEquals("提交必须已经落地", listOf("09:15", "19:15"), first.store.timesOf(1L, 2L))
                assertEquals("两味各一条修订（真的提交了，不是回滚）", 2, first.store.revisions)
                assertEquals(1, first.store.transactions)
                assertTrue("被杀在 reconcile 之前 ⇒ 一次重排都没跑", first.reconciler.allReasons.isEmpty())
                assertTrue("这条路径没有机会入队：进程已经死了", first.queue.reasons.isEmpty())
                assertNull("取消不写标记（库里是什么就是下一次启动的输入）", first.marker.record)
                assertEquals("闹钟仍是旧时刻（W2 的不一致态）", listOf("08:15", "18:15"), first.alarms())

                // 第二次启动 = 新的健康进程，同一份库 / 同一个持久标记。
                first.store.afterCommit = {}
                val second = harness(world = first.world)
                try {
                    val record = second.convergence.convergeNow()

                    assertEquals("库已最新 ⇒ 这一批必须是 NoOp，而不是被跳过", 0, record.advanced)
                    assertEquals("两味都走「未变」分支", 2, record.noOp)
                    assertEquals("零写入：不多开修订、不改钟点", 2, second.store.revisions)
                    assertEquals(listOf("09:15", "19:15"), second.store.timesOf(1L, 2L))
                    // 核心断言：NoOp ⇏ 跳过 reconcile。
                    assertEquals("零变化的启动也必须重排一次", 1, second.reconciler.allReasons.size)
                    assertEquals(listOf(ReminderReconcileReason.ROUTINE_CHANGED), second.reconciler.allReasons)
                    assertEquals("闹钟最终与新钟点一致", listOf("09:15", "19:15"), second.alarms())
                    assertEquals("全量重建：每味药恰一个投影，不叠加不重复", 2, second.reconciler.projections.size)
                    assertEquals(StartupRecomputeStatus.CONVERGED, record.status)
                    assertEquals(StartupRecomputeStatus.CONVERGED, second.marker.record?.status)
                    assertEquals(2, second.marker.record?.noOp ?: -1)
                    assertEquals("第一次没写标记 ⇒ 第二次是首次落记", 1, second.marker.writes)
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
        }

    /**
     * reconcile 失败的三条边界（§9 ① 末句：reconcile 允许吞掉，因为它幂等且有队列/启动兜底）：
     * 1. **不回滚已提交的事务**——reconcile 在事务之外，回滚只可能发生在提交之前；
     * 2. **不穿出启动路径**——启动路径不得因为闹钟故障而崩/被拖垮；
     * 3. **一定留下重试**——`ReconcileRemindersUseCase.all` 无条件 `enqueue`，成功失败都入队，
     *    而且那次重试走同一条 `all()`，跑完闹钟就对了。
     *
     * 标记只记「批次收敛」（`CONVERGED`）：它不声称闹钟已经重排——这一点由下面那条
     * 「闹钟仍是旧时刻」的断言钉住，任何把它当成功凭据的读法都会与这条断言冲突。
     */
    @Test
    fun `a reconcile failure after the commit neither rolls back nor escapes and always leaves a retry`() = runTest {
        val harness = harness()
        try {
            harness.reconciler.behavior = { throw IllegalStateException("AlarmManager unavailable") }

            val outcome = runCatching { harness.convergence.convergeNow() }

            assertTrue("reconcile 失败不得穿出启动路径", outcome.isSuccess)
            assertEquals("提交不得因 reconcile 失败回滚", listOf("09:15", "19:15"), harness.store.timesOf(1L, 2L))
            assertEquals(2, harness.store.revisions)
            assertEquals(1, harness.store.transactions)
            assertEquals("如实：这次没排上，闹钟仍是旧时刻", listOf("08:15", "18:15"), harness.alarms())
            assertEquals("无条件入队：失败也留下重试", listOf(ReminderReconcileReason.ROUTINE_CHANGED), harness.queue.reasons)
            assertEquals(StartupRecomputeStatus.CONVERGED, outcome.getOrThrow().status)
            assertEquals(2, outcome.getOrThrow().advanced)

            // 重试路径真的能修好：同一条 all()，健康的调度器。
            harness.reconciler.behavior = {}
            harness.reconcile.all(ReminderReconcileReason.ROUTINE_CHANGED)

            assertEquals("重试后闹钟与新钟点一致", listOf("09:15", "19:15"), harness.alarms())
            assertEquals("重试是幂等全量重建，不是叠加", 2, harness.reconciler.projections.size)
            assertEquals(2, harness.reconciler.allReasons.size)
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
        world: World = World(medications),
    ): Harness {
        val store = world.store
        val dao: MedicationDao = mock()
        whenever(dao.getAllMedicationsOnce(recipientId)).thenAnswer { store.snapshot() }
        whenever(dao.updatePlan(any(), any())).thenAnswer { store.onUpdate(it.getArgument(0)) }
        val activeRecipient: ActiveRecipientStore = mock()
        whenever(activeRecipient.current()).thenReturn(recipientId)
        val preferences: UserPreferencesRepository = mock()
        whenever(preferences.routineScheduleFor(recipientId)).thenReturn(schedule)
        val repository = MedicationRepositoryImpl(dao, store, activeRecipient, TickingClock())
        val reconciler = world.reconciler
        val queue = world.queue
        val reconcile = ReconcileRemindersUseCase(reconciler, queue)
        val resync = ResyncRemindersUseCase(repository, reconcile)
        val marker = world.marker
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
            world = world,
        )
    }

    private fun repoFile(relative: String): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("找不到仓库根目录（settings.gradle.kts）")
        return File(root, relative)
    }

    /**
     * 一个「进程之外的世界」：库、闹钟投影、重试队列、持久标记。
     * 第二次启动复用它 = 同一个 App 数据目录，只是换了一个进程（新作用域、新收敛对象）。
     */
    private class World(initial: List<Medication>) {
        val store = PlanStore(initial)

        /** 既有闹钟：按库里当前（= 改动前的旧）钟点预置，好让「闹钟仍是旧的」可断言。 */
        val reconciler = RecordingReconciler(store)
        val queue = RecordingQueue()
        val marker = FakeStartupMarker()
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
        val world: World,
    ) {
        fun close() = scope.cancel()

        /** 当前闹钟投影（按药 id 1、2 取值）；`<未排期>` = 投影里没有它。 */
        fun alarms(): List<String> = ALARM_IDS.map { reconciler.projections[it] ?: "<未排期>" }
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

        /**
         * 测试钩子：**事务已提交、控制权还没回到调用方**时执行（W2 的终止注入点）。
         * 抛 `CancellationException` 即「提交落地了，进程随即死亡，下一行 `reconcile` 永远不跑」。
         */
        var afterCommit: () -> Unit = {}

        fun committed(): List<Medication> = rows.values.toList()

        fun snapshot(): List<Medication> {
            snapshots++
            return committed()
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
                // Room 语义：block 正常返回 = 提交；先落地，再给测试注入「提交之后」的终止。
                pending.forEach { rows[it.id] = it }
                revisions += pending.size
                pending.clear()
                afterCommit()
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

    /**
     * 假的投影层：`reconcileAll` = 真实的**全量重建**语义（先清后按库事实重建，见
     * `AndroidReminderReconciler.reconcileAll`），因此「不重复」是结构性的；
     * 构造时按库里旧钟点预置一份已有投影 = 改动前就排着的闹钟。
     */
    private class RecordingReconciler(private val store: PlanStore) : ReminderReconciler {
        val allReasons = mutableListOf<ReminderReconcileReason>()

        /** id -> 已排钟点（= `alarm_projection_registry` 的投影视图）。 */
        val projections = linkedMapOf<Long, String>().apply {
            store.committed().forEach { put(it.id, it.reminderTimes) }
        }

        /** 测试钩子：重建前抛异常（模拟 AlarmManager / 调度故障）。 */
        var behavior: () -> Unit = {}

        override suspend fun reconcileMedication(id: MedicationId, reason: ReminderReconcileReason) = Unit

        override suspend fun reconcileCareTask(id: Long, reason: ReminderReconcileReason) = Unit

        override suspend fun reconcileAll(reason: ReminderReconcileReason) {
            allReasons += reason
            behavior()
            projections.clear()
            store.committed().forEach { projections[it.id] = it.reminderTimes }
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

/** 断言投影时按这个顺序取药（= [DEFAULT_MEDICATIONS] 的 id）。 */
private val ALARM_IDS = listOf(1L, 2L)

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
