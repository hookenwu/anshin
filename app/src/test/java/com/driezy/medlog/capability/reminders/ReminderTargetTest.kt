package com.driezy.medlog.capability.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒目标与编号空间的单测（T2）。
 *
 * 覆盖：序列化往返、旧格式解析、非法输入拒绝、两类实体的 requestCode 区间不重叠。
 */
class ReminderTargetTest {

    @Test
    fun serializeAndParseRoundTrip() {
        val medication = ReminderTarget(recipientId = 2, type = ReminderTargetType.MEDICATION, id = 13)
        val careTask = ReminderTarget(recipientId = 7, type = ReminderTargetType.CARE_TASK, id = 5)

        assertEquals("2:med:13", medication.serialize())
        assertEquals("7:task:5", careTask.serialize())
        assertEquals(medication, ReminderTarget.parse(medication.serialize()))
        assertEquals(careTask, ReminderTarget.parse(careTask.serialize()))
    }

    @Test
    fun parseRejectsUnknownAndLegacyShapes() {
        // 未知类型段
        assertNull(ReminderTarget.parse("2:foo:13"))
        // 非数字
        assertNull(ReminderTarget.parse("x:med:13"))
        assertNull(ReminderTarget.parse("2:med:y"))
        // 负数
        assertNull(ReminderTarget.parse("-1:med:13"))
        // 阶段 1 的旧格式 `<recipientId>:<id>`（由 parseLegacyRecipientKey 处理）
        assertNull(ReminderTarget.parse("2:13"))
        // 更早的裸 id
        assertNull(ReminderTarget.parse("13"))
        assertNull(ReminderTarget.parse(""))
    }

    @Test
    fun parseLegacyRecipientKeyReadsStageOneFormatAsMedication() {
        val legacy = ReminderTarget.parseLegacyRecipientKey("2:13")

        assertEquals(ReminderTarget(2, ReminderTargetType.MEDICATION, 13), legacy)
        // 只认识两段形式
        assertNull(ReminderTarget.parseLegacyRecipientKey("2:med:13"))
        assertNull(ReminderTarget.parseLegacyRecipientKey("13"))
    }

    @Test
    fun medicationKeepsLegacyRequestCodeValues() {
        val medication = ReminderTarget(recipientId = 1, type = ReminderTargetType.MEDICATION, id = 13)

        // 与改造前一致：id * 100 + slot（以及 +50_000 / +100_000 两个偏移）
        assertEquals(13 * 100 + 3, medication.slotRequestCode(3))
        assertEquals(13 * 100 + 3 + 50_000, medication.earlyReminderRequestCode(3))
        assertEquals(13 * 100 + 3 + 100_000, medication.followUpRequestCode(3))
    }

    @Test
    fun careTaskCodesLiveInTheirOwnRange() {
        val careTask = ReminderTarget(recipientId = 1, type = ReminderTargetType.CARE_TASK, id = 5)

        assertEquals(CARE_TASK_CODE_BASE + 5 * 100, careTask.slotRequestCode(0))
        assertTrue(careTask.followUpRequestCode(99) > CARE_TASK_CODE_BASE)
    }

    @Test
    fun medicationAndCareTaskCodeRangesDoNotOverlap() {
        // 取一个远大于真实用量的上界：id < 90 万（本地自增，实际量级为个位到千位）。
        // 精确边界：用药需 id ≲ 99.8 万才严格小于照护区间下限，见 CARE_TASK_CODE_BASE 注释。
        val maxId = 900_000L
        val highestMedicationCode = ReminderTarget(1, ReminderTargetType.MEDICATION, maxId - 1)
            .followUpRequestCode(ReminderTarget.SLOT_STRIDE - 1)
        val lowestCareTaskCode = ReminderTarget(1, ReminderTargetType.CARE_TASK, 1).slotRequestCode(0)

        assertTrue(
            "用药编号上限 $highestMedicationCode 必须小于照护事项编号下限 $lowestCareTaskCode",
            highestMedicationCode < lowestCareTaskCode,
        )
    }

    @Test
    fun everyRequestCodeStaysWithinIntRange() {
        val maxId = 900_000L
        val codes = listOf(
            ReminderTarget(1, ReminderTargetType.MEDICATION, maxId - 1),
            ReminderTarget(1, ReminderTargetType.CARE_TASK, maxId - 1),
        ).flatMap { target ->
            listOf(
                target.slotRequestCode(ReminderTarget.SLOT_STRIDE - 1),
                target.earlyReminderRequestCode(ReminderTarget.SLOT_STRIDE - 1),
                target.followUpRequestCode(ReminderTarget.SLOT_STRIDE - 1),
            )
        }

        codes.forEach { assertTrue("requestCode 必须为正: $it", it > 0) }
    }
}
