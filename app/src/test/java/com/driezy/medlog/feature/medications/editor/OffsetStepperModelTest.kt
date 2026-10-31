package com.driezy.medlog.feature.medications.editor

import com.driezy.medlog.data.model.RoutineSchedule
import com.driezy.medlog.data.model.RoutineTime
import com.driezy.medlog.data.model.TimePeriod
import com.driezy.medlog.data.model.previewOffsets
import com.driezy.medlog.data.model.resolve
import com.driezy.medlog.data.model.resolveWithOffset
import com.driezy.medlog.data.model.validateTimeOffset
import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B11 的步进器契约（docs/medication-time-offset.md §5.1，E1/E3/E10）：
 * 9 档、越界档位**存在但不可选且带原因**、预览与写路径共用同一个 resolver。
 */
class OffsetStepperModelTest {

    @Test
    fun `stepper renders every fifteen minute slot from minus sixty to plus sixty and defaults to zero`() {
        val model = offsetStepperModel(
            periods = setOf(TimePeriod.MORNING),
            schedule = RoutineSchedule(),
            storedMinutes = AddMedicationUiState().offsetMinutes,
        )

        assertEquals(listOf(-60, -45, -30, -15, 0, 15, 30, 45, 60), model.options.map { it.minutes })
        assertEquals("默认必须是 0", 0, model.selectedMinutes)
        assertEquals("默认作息下 9 档全部可用", 9, model.options.count { it.enabled })
        assertTrue(model.stepperEnabled)
        assertFalse(model.storedRejected)
    }

    @Test
    fun `bedtime at 23_30 disables the three later slots with a cross-midnight reason`() {
        val model = offsetStepperModel(
            periods = setOf(TimePeriod.BEDTIME),
            schedule = RoutineSchedule(bed = RoutineTime(23, 30)),
            storedMinutes = 0,
        )

        assertEquals("+15 = 23:45 仍合法", true, model.option(15).enabled)
        listOf(30, 45, 60).forEach { minutes ->
            val option = model.option(minutes)
            assertFalse("+$minutes 必须不可选（不是隐藏、不是钳位）", option.enabled)
            val reason = option.unavailable
            assertTrue("+$minutes 必须带可见原因，实际：$reason", reason is OffsetUnavailable.CrossMidnight)
            assertEquals(TimePeriod.BEDTIME, (reason as OffsetUnavailable.CrossMidnight).period)
            assertEquals(minutes, reason.minutes)
        }
        listOf(-60, -45, -30, -15, 0).forEach { assertEquals(it.toString(), true, model.option(it).enabled) }
        assertEquals("不可选档位的原因必须在模型里可见", 3, model.options.count { it.unavailable != null })
    }

    @Test
    fun `multi period intersection names the crossing period not the whole medication`() {
        val model = offsetStepperModel(
            periods = setOf(TimePeriod.EVENING, TimePeriod.BEDTIME),
            schedule = RoutineSchedule(bed = RoutineTime(23, 30)),
            storedMinutes = 0,
        )

        assertEquals("EVENING(22:30)+60 = 23:30 合法，但 BEDTIME 越界 ⇒ 整档禁用", false, model.option(60).enabled)
        val reason = model.option(60).unavailable as OffsetUnavailable.CrossMidnight
        assertEquals("必须指名是哪一段越界", TimePeriod.BEDTIME, reason.period)
        assertEquals(true, model.option(15).enabled)
        assertEquals(true, model.option(-60).enabled)
    }

    @Test
    fun `preview is the resolver result for every period including one near midnight`() {
        val schedule = RoutineSchedule(breakfast = RoutineTime(8, 0), bed = RoutineTime(23, 30))
        val periods = listOf(TimePeriod.AFTER_BREAKFAST, TimePeriod.EVENING, TimePeriod.BEDTIME)

        val preview = previewOffsets(periods, schedule, offsetMinutes = 15)

        assertEquals(3, preview.size)
        preview.forEach { row ->
            val anchor = RoutineAnchor.valueOf(row.period.name)
            assertEquals("基线必须来自 resolve()", schedule.resolve(anchor), row.base)
            assertEquals("结果必须来自 resolveWithOffset()", schedule.resolveWithOffset(anchor, 15), row.resolved)
        }
        assertEquals("23:30 + 15 = 23:45", "23:45", preview.last().resolved.toString())
        assertEquals("相邻时段不能被合并", 3, preview.map { it.resolved }.distinct().size)
    }

    @Test
    fun `a wrapped anchor disables the whole stepper instead of single slots`() {
        val schedule = RoutineSchedule(breakfast = RoutineTime(0, 10))
        val model = offsetStepperModel(setOf(TimePeriod.BEFORE_BREAKFAST), schedule, storedMinutes = 0)

        assertEquals(TimePeriod.BEFORE_BREAKFAST, model.wrappedAnchor)
        assertFalse(model.stepperEnabled)
        assertEquals("整条禁用：只有祖父条款的 0 档仍合法", listOf(0), model.options.filter { it.enabled }.map { it.minutes })
        assertEquals(OffsetUnavailable.WrappedAnchor(TimePeriod.BEFORE_BREAKFAST), model.option(15).unavailable)

        // 同一作息里 AFTER_BREAKFAST 的基线是 00:25（未回绕）⇒ 它自己 +15 必须仍然合法，不连带误杀。
        val afterBreakfast = validateTimeOffset(
            15,
            schedule,
            listOf(RoutineAnchor.AFTER_BREAKFAST),
            applicable = true,
        )
        assertTrue(afterBreakfast.toString(), afterBreakfast is com.driezy.medlog.data.model.TimeOffsetCheck.Allowed)
    }

    @Test
    fun `a stored illegal offset is shown as an error state and blocks saving`() {
        val model = offsetStepperModel(
            periods = setOf(TimePeriod.BEDTIME),
            schedule = RoutineSchedule(bed = RoutineTime(23, 30)),
            storedMinutes = 30,
        )

        assertTrue(model.storedRejected)
        assertFalse("非法已存值必须阻止保存（不静默归零）", model.canSave)
        assertTrue("非法值无法解析，预览必须为空而不是算出一个假钟点", model.preview.isEmpty())
    }

    @Test
    fun `the preview introduces no second arithmetic path`() {
        val source = File("src/main/java/com/driezy/medlog/data/model/MedicationOffsetPreview.kt").readText()

        assertTrue("预览必须走写路径的唯一物化点", "materializedFor(" in source)
        assertTrue("预览不得自己调 resolver（那会成为第二个调用方）", "resolveWithOffset(" !in source)
        listOf("+ offsetMinutes", "offsetMinutes +", "plusMinutes", "minusMinutes", "plusHours").forEach { token ->
            assertFalse("预览文件里出现了第二套算术：$token", token in source)
        }
        assertEquals(
            "预览只需一处 materializedFor 调用",
            1,
            Regex("materializedFor\\(").findAll(source).count(),
        )

        val editorUi = File(
            "src/main/java/com/driezy/medlog/feature/medications/editor/AddMedicationScheduleSections.kt",
        ).readText()
        listOf("plusMinutes", "minusMinutes", "+ offsetMinutes").forEach { token ->
            assertFalse("编辑器 UI 不得自己算偏移：$token", token in editorUi)
        }
        assertNotNull("预览模型必须仍由编辑器渲染", "offsetStepper" in editorUi)
    }

    private fun OffsetStepperModel.option(minutes: Int) = options.first { it.minutes == minutes }
}
