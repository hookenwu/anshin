package com.driezy.medlog.capability.reminders

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.domain.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * T6b：照护事项排期计算（纯映射，不依赖 Android 运行时）。
 *
 * 覆盖 [careTaskReminderOccurrences]——它与用药 `scheduleAllReminders` 共用同一个
 * [ReminderPlanner]，只是把锚点换成照护事项的最后一次完成；并核对 requestCode 落在
 * `CARE_TASK_CODE_BASE + id*100 + slot` 的独立编号空间内。
 */
class CareTaskReminderSchedulingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-01-10T04:00:00Z") // 2026-01-10 12:00 CST
    private val planner = ReminderPlanner(Clock.fixed(now, zone))

    @Test
    fun `fixed times schedule every slot inside the care-task requestCode space`() {
        val task = task(id = 5L, reminderTimes = "08:00,14:00,20:00")

        val occurrences = careTaskReminderOccurrences(planner, task, null, emptySet(), zone)

        assertEquals(3, occurrences.size)
        assertEquals(
            listOf(
                Instant.parse("2026-01-11T00:00:00Z"), // 次日 08:00（今日已过）
                Instant.parse("2026-01-10T06:00:00Z"), // 今日 14:00
                Instant.parse("2026-01-10T12:00:00Z"), // 今日 20:00
            ),
            occurrences.map { it.scheduledAt },
        )
        val target = ReminderTarget(RECIPIENT_ID, ReminderTargetType.CARE_TASK, 5L)
        occurrences.forEach { occurrence ->
            val code = target.slotRequestCode(occurrence.slotIndex)
            assertEquals(CARE_TASK_CODE_BASE + 5 * 100 + occurrence.slotIndex, code)
            assertTrue("照护事项编号必须独立于用药空间", code >= CARE_TASK_CODE_BASE)
        }
    }

    @Test
    fun `interval next occurrence is anchored on the last completion`() {
        val task = task(id = 6L, scheduleKind = CareTaskScheduleKind.INTERVAL, intervalHours = 2)
        val lastDoneMs = Instant.parse("2026-01-10T03:00:00Z").toEpochMilli()

        val occurrence = careTaskReminderOccurrences(planner, task, lastDoneMs, emptySet(), zone).single()

        assertEquals(Instant.parse("2026-01-10T05:00:00Z"), occurrence.scheduledAt)
    }

    @Test
    fun `interval without a completion advances from the plan start`() {
        val task = task(id = 6L, scheduleKind = CareTaskScheduleKind.INTERVAL, intervalHours = 4)

        val occurrence = careTaskReminderOccurrences(planner, task, null, emptySet(), zone).single()

        assertTrue("下一个间隔时刻必须在 now 之后", occurrence.scheduledAt > now)
        assertEquals(0L, occurrence.scheduledAt.epochSecond % (4 * 3600L))
    }

    @Test
    fun `as needed tasks never schedule an occurrence`() {
        val task = task(id = 7L, scheduleKind = CareTaskScheduleKind.AS_NEEDED)

        assertTrue(careTaskReminderOccurrences(planner, task, null, emptySet(), zone).isEmpty())
    }

    @Test
    fun `handled slots advance to the next occurrence`() {
        val task = task(id = 8L, reminderTimes = "08:00")
        val tomorrow = Instant.parse("2026-01-11T00:00:00Z")

        val occurrence = careTaskReminderOccurrences(planner, task, null, setOf(tomorrow), zone).single()

        assertEquals(Instant.parse("2026-01-12T00:00:00Z"), occurrence.scheduledAt)
    }

    @Test
    fun `end date stops scheduling`() {
        val task = task(id = 9L, reminderTimes = "08:00")
            .copy(endDate = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli())

        assertTrue(careTaskReminderOccurrences(planner, task, null, emptySet(), zone).isEmpty())
    }

    private fun task(
        id: Long,
        scheduleKind: CareTaskScheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        reminderTimes: String = "08:00",
        intervalHours: Int = 0,
    ) = CareTask(
        id = id,
        careRecipientId = RECIPIENT_ID,
        title = "测试事项",
        category = CareTaskCategory.OTHER,
        completionMode = CareTaskCompletionMode.TOGGLE,
        scheduleKind = scheduleKind,
        reminderTimes = reminderTimes,
        intervalHours = intervalHours,
        startDate = 0L,
    )

    private companion object {
        const val RECIPIENT_ID = 7L
    }
}
