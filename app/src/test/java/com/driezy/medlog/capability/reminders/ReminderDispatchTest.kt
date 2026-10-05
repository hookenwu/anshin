package com.driezy.medlog.capability.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 类型化派发决策的单测（T3）。
 *
 * 重点：升级后旧安装包排出的闹钟（只带 `EXTRA_MED_ID`、无 type extra）必须仍走用药通路；
 * 照护事项必须独立成路，且不得借用用药 id 兜底。
 */
class ReminderDispatchTest {

    private fun decide(
        targetTypeKey: String? = null,
        targetId: Long = ReminderDispatch.NO_TARGET_ID,
        medicationId: Long = ReminderDispatch.NO_TARGET_ID,
        isEarly: Boolean = false,
        isFollowUp: Boolean = false,
    ): ReminderDispatch? = ReminderDispatch.decide(
        targetTypeKey = targetTypeKey,
        targetId = targetId,
        medicationId = medicationId,
        isEarly = isEarly,
        isFollowUp = isFollowUp,
    )

    @Test
    fun `missing target type is dispatched as medication for alarms from the previous build`() {
        // 旧版闹钟只带 EXTRA_MED_ID，没有任何 type/id extra
        val dispatch = decide(medicationId = 13L)

        assertEquals(ReminderTargetType.MEDICATION, dispatch?.targetType)
        assertEquals(13L, dispatch?.targetId)
        assertEquals(ReminderKind.MEDICATION_NORMAL, dispatch?.kind)
    }

    @Test
    fun `unknown target type falls back to medication`() {
        val dispatch = decide(targetTypeKey = "not-a-type", medicationId = 7L)

        assertEquals(ReminderTargetType.MEDICATION, dispatch?.targetType)
        assertEquals(7L, dispatch?.targetId)
        assertEquals(ReminderKind.MEDICATION_NORMAL, dispatch?.kind)
    }

    @Test
    fun `explicit medication target id wins over the legacy med id extra`() {
        val dispatch = decide(targetTypeKey = ReminderTargetType.MEDICATION.key, targetId = 99L, medicationId = 13L)

        assertEquals(ReminderTargetType.MEDICATION, dispatch?.targetType)
        assertEquals(99L, dispatch?.targetId)
    }

    @Test
    fun `medication without target id falls back to the legacy med id extra`() {
        val dispatch = decide(targetTypeKey = ReminderTargetType.MEDICATION.key, medicationId = 13L)

        assertEquals(ReminderTargetType.MEDICATION, dispatch?.targetType)
        assertEquals(13L, dispatch?.targetId)
    }

    @Test
    fun `early flag produces the early medication kind`() {
        val dispatch = decide(medicationId = 1L, isEarly = true)

        assertEquals(ReminderKind.MEDICATION_EARLY, dispatch?.kind)
    }

    @Test
    fun `follow up flag produces the follow up medication kind`() {
        val dispatch = decide(medicationId = 1L, isFollowUp = true)

        assertEquals(ReminderKind.MEDICATION_FOLLOW_UP, dispatch?.kind)
    }

    @Test
    fun `early flag wins over follow up when both are set`() {
        val dispatch = decide(medicationId = 1L, isEarly = true, isFollowUp = true)

        assertEquals(ReminderKind.MEDICATION_EARLY, dispatch?.kind)
    }

    @Test
    fun `care task target dispatches to the care task kind with its own id`() {
        val dispatch = decide(targetTypeKey = ReminderTargetType.CARE_TASK.key, targetId = 42L)

        assertEquals(ReminderTargetType.CARE_TASK, dispatch?.targetType)
        assertEquals(42L, dispatch?.targetId)
        assertEquals(ReminderKind.CARE_TASK, dispatch?.kind)
    }

    @Test
    fun `care task ignores the medication flags`() {
        val dispatch = decide(
            targetTypeKey = ReminderTargetType.CARE_TASK.key,
            targetId = 42L,
            isEarly = true,
            isFollowUp = true,
        )

        assertEquals(ReminderKind.CARE_TASK, dispatch?.kind)
    }

    @Test
    fun `care task without target id is dropped instead of borrowing the medication id`() {
        val dispatch = decide(targetTypeKey = ReminderTargetType.CARE_TASK.key, medicationId = 13L)

        assertNull(dispatch)
    }

    @Test
    fun `medication without any id is dropped`() {
        assertNull(decide())
        assertNull(decide(targetTypeKey = ReminderTargetType.MEDICATION.key))
    }

    @Test
    fun `non positive care task id is dropped`() {
        assertNull(decide(targetTypeKey = ReminderTargetType.CARE_TASK.key, targetId = 0L))
    }
}
