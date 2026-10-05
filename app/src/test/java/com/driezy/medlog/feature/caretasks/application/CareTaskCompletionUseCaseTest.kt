package com.driezy.medlog.feature.caretasks.application

import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.data.local.TransactionRunner
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.LogRevisionType
import com.driezy.medlog.data.repository.CareTaskRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.ReminderReconciler
import com.driezy.medlog.domain.ReminderReconciliationQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * T5：照护事项完成语义单测。
 *
 * 覆盖打卡型与时长型两个流程的每个动作，以及"不编造数据"的三条边界：
 * 未开始过就没有时长、开始晚于结束不算时长、已终结记录才叫编辑。
 */
class CareTaskCompletionUseCaseTest {

    // 固定时钟取当地（Asia/Shanghai）白天，避免夹具本身跨午夜把"同日编辑"判成"回溯编辑"
    private val nowMs = 1_763_956_800_000L // 2025-11-24 12:00 CST
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(nowMs), zone)
    private val repository: CareTaskRepository = mock()
    private val reminderReconciler: ReminderReconciler = mock()
    private val reminderQueue: ReminderReconciliationQueue = mock()
    private val useCase = CareTaskCompletionUseCase(
        ImmediateTransactionRunner,
        repository,
        ReconcileRemindersUseCase(reminderReconciler, reminderQueue),
        clock,
    )

    private val taskId = 7L
    private val scheduled = 1_763_942_400_000L // 2025-11-24 08:00 CST，与 now 同一当地日

    @Test
    fun `complete on an empty slot records DONE with the completion time and no duration`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(null)
        whenever(repository.upsertLog(any())).thenReturn(11L)

        val result = useCase.complete(taskId, scheduled)

        val saved = captureSavedLog()
        assertEquals(CareTaskLogStatus.DONE, saved.status)
        assertEquals(nowMs, saved.actualEndMs)
        assertNull("没开始过就不该有时长", saved.actualDurationMinutes)
        assertNull(saved.actualStartMs)
        assertEquals(LogRevisionType.ORIGINAL, saved.revisionType)
        assertEquals(taskId, saved.careTaskId)
        assertEquals(scheduled, saved.scheduledTimeMs)
        assertEquals("新行要带上写回的行号", 11L, result.id)
    }

    @Test
    fun `start records IN_PROGRESS with the start time and no end`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(null)
        whenever(repository.upsertLog(any())).thenReturn(12L)

        useCase.start(taskId, scheduled)

        val saved = captureSavedLog()
        assertEquals(CareTaskLogStatus.IN_PROGRESS, saved.status)
        assertEquals(nowMs, saved.actualStartMs)
        assertNull(saved.actualEndMs)
        assertNull(saved.actualDurationMinutes)
    }

    @Test
    fun `complete after start yields the duration and keeps the original start`() = test {
        val startMs = nowMs - 30 * 60_000L
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 5L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = startMs,
                createdAtMs = startMs,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(5L)

        useCase.complete(taskId, scheduled)

        val saved = captureSavedLog()
        assertEquals(CareTaskLogStatus.DONE, saved.status)
        assertEquals(startMs, saved.actualStartMs)
        assertEquals(nowMs, saved.actualEndMs)
        assertEquals(30, saved.actualDurationMinutes)
        assertEquals("进行中 → 完成是同一条记录的生命周期，不算编辑", LogRevisionType.ORIGINAL, saved.revisionType)
        assertEquals("就地更新，不新建行", 5L, saved.id)
    }

    @Test
    fun `restarting a duration task does not shorten the elapsed time`() = test {
        val startMs = nowMs - 15 * 60_000L
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 6L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = startMs,
                createdAtMs = startMs,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(6L)

        useCase.start(taskId, scheduled)

        assertEquals(startMs, captureSavedLog().actualStartMs)
    }

    @Test
    fun `skip keeps the start but never invents an end or a duration`() = test {
        val startMs = nowMs - 5 * 60_000L
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 8L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = startMs,
                createdAtMs = startMs,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(8L)

        useCase.skip(taskId, scheduled)

        val saved = captureSavedLog()
        assertEquals(CareTaskLogStatus.SKIPPED, saved.status)
        assertEquals("开始过是事实，保留", startMs, saved.actualStartMs)
        assertNull(saved.actualEndMs)
        assertNull(saved.actualDurationMinutes)
    }

    @Test
    fun `skip on a fresh slot records SKIPPED with no times at all`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(null)
        whenever(repository.upsertLog(any())).thenReturn(9L)

        useCase.skip(taskId, scheduled)

        val saved = captureSavedLog()
        assertEquals(CareTaskLogStatus.SKIPPED, saved.status)
        assertNull(saved.actualStartMs)
        assertNull(saved.actualEndMs)
        assertNull(saved.actualDurationMinutes)
    }

    @Test
    fun `undo deletes the slot record`() = test {
        useCase.undo(taskId, scheduled)

        verify(repository).deleteLogForScheduledTime(taskId, scheduled)
    }

    @Test
    fun `notes and posture are preserved when the caller omits them`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 3L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.DONE,
                actualEndMs = scheduled,
                updatedAtMs = scheduled,
                notes = "翻身后拍背",
                postureNote = "左侧",
                createdAtMs = scheduled,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(3L)

        useCase.complete(taskId, scheduled, notes = "", postureNote = null)

        val saved = captureSavedLog()
        assertEquals("翻身后拍背", saved.notes)
        assertEquals("左侧", saved.postureNote)
    }

    @Test
    fun `rewriting a terminal record on the same day is marked as an edit`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 4L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.DONE,
                actualEndMs = scheduled,
                createdAtMs = scheduled,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(4L)

        useCase.skip(taskId, scheduled)

        assertEquals(LogRevisionType.SAME_DAY_EDIT, captureSavedLog().revisionType)
    }

    @Test
    fun `rewriting a terminal record from an earlier day is marked as retroactive`() = test {
        val twoDaysAgo = scheduled - 2 * 24 * 60 * 60 * 1000L
        whenever(repository.getLogForScheduledTime(taskId, twoDaysAgo)).thenReturn(
            CareTaskLog(
                id = 2L,
                careTaskId = taskId,
                scheduledTimeMs = twoDaysAgo,
                status = CareTaskLogStatus.DONE,
                actualEndMs = twoDaysAgo,
                createdAtMs = twoDaysAgo,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(2L)

        useCase.skip(taskId, twoDaysAgo)

        assertEquals(LogRevisionType.RETROACTIVE_EDIT, captureSavedLog().revisionType)
    }

    @Test
    fun `a start later than the end is not turned into a duration`() = test {
        val futureStart = nowMs + 5 * 60_000L
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(
            CareTaskLog(
                id = 10L,
                careTaskId = taskId,
                scheduledTimeMs = scheduled,
                status = CareTaskLogStatus.IN_PROGRESS,
                actualStartMs = futureStart,
                createdAtMs = futureStart,
            ),
        )
        whenever(repository.upsertLog(any())).thenReturn(10L)

        useCase.complete(taskId, scheduled)

        val saved = captureSavedLog()
        assertNull("起止顺序不对就不算时长", saved.actualDurationMinutes)
        assertEquals(futureStart, saved.actualStartMs)
    }

    @Test
    fun `complete re-projects the task once so the interval is anchored on the completion`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(null)
        whenever(repository.upsertLog(any())).thenReturn(11L)

        useCase.complete(taskId, scheduled)

        verify(reminderReconciler).reconcileCareTask(taskId, ReminderReconcileReason.DOSE_RECORDED)
        verify(reminderQueue).enqueue(ReminderReconcileReason.DOSE_RECORDED)
    }

    @Test
    fun `skip re-projects the task once`() = test {
        whenever(repository.getLogForScheduledTime(taskId, scheduled)).thenReturn(null)
        whenever(repository.upsertLog(any())).thenReturn(9L)

        useCase.skip(taskId, scheduled)

        verify(reminderReconciler).reconcileCareTask(taskId, ReminderReconcileReason.DOSE_RECORDED)
    }

    @Test
    fun `undo re-projects the task once`() = test {
        useCase.undo(taskId, scheduled)

        verify(repository).deleteLogForScheduledTime(taskId, scheduled)
        verify(reminderReconciler).reconcileCareTask(taskId, ReminderReconcileReason.DOSE_RECORDED)
    }

    private fun test(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun captureSavedLog(): CareTaskLog {
        val captor = argumentCaptor<CareTaskLog>()
        verify(repository).upsertLog(captor.capture())
        return captor.firstValue
    }
}

/** 单测里不需要真事务：直接执行，保留读改写的顺序语义。 */
private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
}
