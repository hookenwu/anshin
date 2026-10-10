package com.driezy.medlog.capability.reminders

import com.driezy.medlog.data.recipient.ActiveRecipientStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影登记项集合操作的单测。
 *
 * 重点回归：删除 / 归档 / 成员删除后登记项必须被撤销——phantom `1:task:1` 就是这样漏掉的。
 * 纯字符串集合，不依赖 Android 运行时。
 */
class AlarmProjectionRegistryTest {

    @Test
    fun `register serializes the three segment form and is idempotent`() {
        val target = ReminderTarget(1L, ReminderTargetType.CARE_TASK, 1L)

        val once = AlarmProjectionRegistry.register(emptySet(), target)
        val twice = AlarmProjectionRegistry.register(once, target)

        assertEquals(setOf("1:task:1"), once)
        assertEquals(once, twice)
    }

    @Test
    fun `unregister removes a care task three segment entry`() {
        val entries = setOf("1:task:1", "1:med:13", "2:med:13")

        val updated = AlarmProjectionRegistry.unregister(
            entries,
            ReminderTarget(1L, ReminderTargetType.CARE_TASK, 1L),
        )

        assertEquals(setOf("1:med:13", "2:med:13"), updated)
    }

    @Test
    fun `unregister removes medication legacy shapes but not a same numbered care task`() {
        val entries = setOf("2:med:13", "2:13", "13", "2:task:13")

        val updated = AlarmProjectionRegistry.unregister(
            entries,
            ReminderTarget(2L, ReminderTargetType.MEDICATION, 13L),
        )

        // 用药三种历史写法都清掉，同号的照护事项登记项原样保留
        assertEquals(setOf("2:task:13"), updated)
    }

    @Test
    fun `an unknown recipient cannot remove a real entry but identity matching does`() {
        val entries = setOf("1:task:1")

        // 旧路径：删除后拿不到成员 id，退化成按 (0, id) 撤销——真实条目纹丝不动（就是本 bug）
        val viaUnknownRecipient = AlarmProjectionRegistry.unregister(
            entries,
            ReminderTarget(ActiveRecipientStore.NO_RECIPIENT, ReminderTargetType.CARE_TASK, 1L),
        )
        assertEquals(entries, viaUnknownRecipient)

        // 修复路径：按 (type, id) 跨成员匹配 → 命中真实条目
        assertEquals(
            listOf(ReminderTarget(1L, ReminderTargetType.CARE_TASK, 1L)),
            AlarmProjectionRegistry.matchingIdentity(entries, ReminderTargetType.CARE_TASK, 1L),
        )
    }

    @Test
    fun `identity matching finds medication entries including legacy shapes and never a care task`() {
        val entries = setOf("3:med:1", "4:1", "1", "3:task:1")

        val matched = AlarmProjectionRegistry.matchingIdentity(entries, ReminderTargetType.MEDICATION, 1L)

        assertTrue(matched.contains(ReminderTarget(3L, ReminderTargetType.MEDICATION, 1L)))
        assertTrue(matched.contains(ReminderTarget(4L, ReminderTargetType.MEDICATION, 1L)))
        assertTrue(
            matched.contains(ReminderTarget(ActiveRecipientStore.NO_RECIPIENT, ReminderTargetType.MEDICATION, 1L)),
        )
        assertFalse("同号照护事项不得被用药清理误伤", matched.any { it.type == ReminderTargetType.CARE_TASK })
    }

    @Test
    fun `orphaned returns dead targets and keeps live ones`() {
        val entries = setOf(
            "7:task:1", // 存活照护事项
            "7:med:4", // 存活用药
            "8:med:13", // 成员已删除 / 药品已硬删除
            "0:med:9", // 阶段 0 遗留裸登记
            "7:1", // 阶段 1 遗留用药旧格式，已不在存活集
        )
        val isLive: (ReminderTarget) -> Boolean = { target ->
            target.recipientId == 7L &&
                when (target.type) {
                    ReminderTargetType.MEDICATION -> target.id == 4L
                    ReminderTargetType.CARE_TASK -> target.id == 1L
                    // 照护事件不进闹钟投影登记表（无此类型条目）。
                    ReminderTargetType.CARE_EVENT -> false
                }
        }

        val orphans = AlarmProjectionRegistry.orphaned(entries, isLive)

        assertTrue("存活照护事项不能被清理", ReminderTarget(7L, ReminderTargetType.CARE_TASK, 1L) !in orphans)
        assertTrue("存活用药不能被清理", ReminderTarget(7L, ReminderTargetType.MEDICATION, 4L) !in orphans)
        assertTrue(orphans.contains(ReminderTarget(8L, ReminderTargetType.MEDICATION, 13L)))
        assertTrue(orphans.contains(ReminderTarget(0L, ReminderTargetType.MEDICATION, 9L)))
        assertTrue("旧格式 7:1 解析为用药目标后同样落入孤儿", orphans.contains(ReminderTarget(7L, ReminderTargetType.MEDICATION, 1L)))
        assertEquals(3, orphans.size)
    }

    @Test
    fun `empty registry yields no targets`() {
        assertEquals(emptyList<ReminderTarget>(), AlarmProjectionRegistry.parseAll(emptySet()))
        assertEquals(emptyList<ReminderTarget>(), AlarmProjectionRegistry.orphaned(emptySet()) { true })
    }
}
