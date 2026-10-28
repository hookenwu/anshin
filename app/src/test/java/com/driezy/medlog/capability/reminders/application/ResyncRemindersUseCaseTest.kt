package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.repository.MedicationRepository
import com.driezy.medlog.domain.ReminderReconcileReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * 用例只剩两件事（docs/medication-time-offset.md §3.5 / §9 ①）：
 * 调**一个**批量入口 → **提交之后**重排提醒；失败原样穿出、不吞、不重排。
 *
 * 「哪些药要改、改成几点」不再由本用例决定，所以那几条断言搬到了 [PlanRoutineBatchTest]
 * （同名用例保留语义：`exact and as-needed plans are not rewritten`、多时段的整体回写）。
 */
class ResyncRemindersUseCaseTest {
    @Test
    fun `routine plans are persisted as one atomic batch before projections reconcile`() = runTest {
        val medications: MedicationRepository = mock()
        val reconciler: ReconcileRemindersUseCase = mock()
        val schedule = RoutineSchedule(breakfast = RoutineTime(9, 15), dinner = RoutineTime(19, 45))

        ResyncRemindersUseCase(medications, reconciler)(schedule)

        inOrder(medications, reconciler) {
            verify(medications).planForRoutine(schedule)
            verify(reconciler).all(ReminderReconcileReason.ROUTINE_CHANGED)
        }
        verify(medications, never()).updateMedication(any())
    }

    @Test
    fun `a rejected batch propagates and reminders are not reconciled`() = runTest {
        val medications: MedicationRepository = mock()
        val reconciler: ReconcileRemindersUseCase = mock()
        val conflict = RoutineResyncConflictException(
            listOf(
                OffsetConflict(
                    medicationId = 1L,
                    name = "Medication 1",
                    anchor = null,
                    minutes = 30,
                    base = null,
                    resolvedMinuteOfDay = 1440,
                    reason = com.driezy.medlog.data.model.TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
                ),
            ),
        )
        whenever(medications.planForRoutine(any(), any())).thenThrow(conflict)

        val error = runCatching { ResyncRemindersUseCase(medications, reconciler)(RoutineSchedule()) }.exceptionOrNull()

        assertTrue("冲突必须原样穿出（§9 ① 禁止在 resync 外层 runCatching），实际：$error", error === conflict)
        verify(reconciler, never()).all(any())
    }

    @Test
    fun `the use case holds no time arithmetic of its own`() {
        val source = File("src/main/java/com/driezy/medlog/capability/reminders/application/ResyncRemindersUseCase.kt")
            .takeIf(File::exists)
            ?: repoFile(
                "app/src/main/java/com/driezy/medlog/capability/reminders/application/ResyncRemindersUseCase.kt",
            )
        val code = source.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse("用例不得再解析时段：$source", "TimePeriods.parse" in code)
        assertFalse(
            "用例不得再调 resolve(（offset 感知的物化只允许发生在 materializedFor）",
            Regex("""\bresolve\(""").containsMatchIn(code),
        )
        assertFalse("用例不得再用 withResolvedRoutineTimes 直接拼钟点", "withResolvedRoutineTimes" in code)
        assertTrue("唯一允许的判定入口是 planForRoutine", "planForRoutine(" in code)
    }

    private fun repoFile(relative: String): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
            ?: error("找不到仓库根目录（settings.gradle.kts）")
        return File(root, relative)
    }
}
