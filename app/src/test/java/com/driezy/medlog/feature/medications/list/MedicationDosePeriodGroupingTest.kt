package com.driezy.medlog.feature.medications.list

import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.TimePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的药品「按时段分组」（docs/medication-grouping.md §5）。
 *
 * 这里只测纯函数：分类来源、多时段重复显示、无时段兜底、顺序稳定性，以及
 * 「重复显示不会产生第二个实体 / 选择集按 id 去重」这条不变量。
 */
class MedicationDosePeriodGroupingTest {

    private fun med(
        id: Long,
        timePeriod: String,
        prn: Boolean = false,
        intervalHours: Int = 0,
        archived: Boolean = false,
        frequencyType: String = "daily",
        frequencyInterval: Int = 1,
    ) = Medication(
        id = id,
        name = "药$id",
        doseUnit = "片",
        timePeriod = timePeriod,
        isPRN = prn,
        intervalHours = intervalHours,
        isArchived = archived,
        frequencyType = frequencyType,
        frequencyInterval = frequencyInterval,
    )

    private fun List<MedicationPeriodGroup>.idsOf(group: DosePeriodGroup): List<Long>? =
        firstOrNull { it.group == group }?.medications?.map { it.id }

    private fun List<MedicationPeriodGroup>.rows(): List<Medication> = flatMap { it.medications }

    @Test
    fun `a medication with morning and noon periods appears in both groups`() {
        val multi = med(1, "afterBreakfast,afterLunch")

        val groups = listOf(multi).groupedByDosePeriod()

        assertEquals(listOf(1L), groups.idsOf(DosePeriodGroup.MORNING))
        assertEquals(listOf(1L), groups.idsOf(DosePeriodGroup.NOON))
        assertNull(groups.idsOf(DosePeriodGroup.EVENING))
        assertEquals(listOf(DosePeriodGroup.MORNING, DosePeriodGroup.NOON), groups.map { it.group })
    }

    @Test
    fun `a bedtime only medication lands in bedtime and never in the evening group`() {
        val groups = listOf(med(1, "bedtime")).groupedByDosePeriod()

        assertEquals(listOf(1L), groups.idsOf(DosePeriodGroup.BEDTIME))
        assertNull("睡前单独成组，不并进「晚」", groups.idsOf(DosePeriodGroup.EVENING))
    }

    @Test
    fun `every declared period maps to exactly one group and exact maps to none`() {
        val expected = mapOf(
            TimePeriod.EXACT to null,
            TimePeriod.BEFORE_BREAKFAST to DosePeriodGroup.MORNING,
            TimePeriod.MORNING to DosePeriodGroup.MORNING,
            TimePeriod.AFTER_BREAKFAST to DosePeriodGroup.MORNING,
            TimePeriod.BEFORE_LUNCH to DosePeriodGroup.NOON,
            TimePeriod.AFTER_LUNCH to DosePeriodGroup.NOON,
            TimePeriod.AFTERNOON to DosePeriodGroup.NOON,
            TimePeriod.BEFORE_DINNER to DosePeriodGroup.EVENING,
            TimePeriod.AFTER_DINNER to DosePeriodGroup.EVENING,
            TimePeriod.EVENING to DosePeriodGroup.EVENING,
            TimePeriod.BEDTIME to DosePeriodGroup.BEDTIME,
        )

        assertEquals(TimePeriod.entries.toSet(), expected.keys)
        expected.forEach { (period, group) ->
            assertEquals("$period 的组", group, period.dosePeriodGroupOrNull())
        }
    }

    @Test
    fun `prn and interval dosing ignore a stale period and fall into the no period group`() {
        val prn = med(1, "afterLunch", prn = true)
        val interval = med(2, "morning", intervalHours = 6)

        val groups = listOf(prn, interval).groupedByDosePeriod()

        assertEquals(1, groups.size)
        assertEquals(DosePeriodGroup.WITHOUT_PERIOD, groups.single().group)
        assertEquals(listOf(1L, 2L), groups.single().medications.map { it.id })
    }

    @Test
    fun `exact unknown and empty periods all land in the no period group`() {
        val meds = listOf(
            med(1, "exact"),
            med(2, "whenever"),
            med(3, ""),
            med(4, "exact"),
        )

        val groups = meds.groupedByDosePeriod()

        assertEquals(DosePeriodGroup.WITHOUT_PERIOD, groups.single().group)
        assertEquals(listOf(1L, 2L, 3L, 4L), groups.single().medications.map { it.id })
    }

    @Test
    fun `parsing drops exact and trims keys instead of trusting the raw string`() {
        val groups = listOf(
            med(1, "exact,afterBreakfast"),
            med(2, "  morning , afterLunch "),
        ).groupedByDosePeriod()

        assertEquals(listOf(1L, 2L), groups.idsOf(DosePeriodGroup.MORNING))
        assertEquals(listOf(2L), groups.idsOf(DosePeriodGroup.NOON))
        assertNull(groups.idsOf(DosePeriodGroup.WITHOUT_PERIOD))
    }

    @Test
    fun `a day recurrence still groups by period`() {
        val everyOtherDay = med(1, "afterDinner", frequencyType = "interval", frequencyInterval = 2)

        val groups = listOf(everyOtherDay).groupedByDosePeriod()

        assertEquals(listOf(1L), groups.idsOf(DosePeriodGroup.EVENING))
    }

    @Test
    fun `an empty list yields no groups at all`() {
        assertTrue(emptyList<Medication>().groupedByDosePeriod().isEmpty())
    }

    @Test
    fun `a single group still renders as one group with a list`() {
        val groups = listOf(med(1, "beforeLunch"), med(2, "afternoon")).groupedByDosePeriod()

        assertEquals(1, groups.size)
        assertEquals(DosePeriodGroup.NOON, groups.single().group)
        assertEquals(listOf(1L, 2L), groups.single().medications.map { it.id })
    }

    @Test
    fun `groups render early to night and empty groups are omitted`() {
        val groups = listOf(
            med(1, "evening"),
            med(2, "morning"),
            med(3, "bedtime"),
            med(4, "exact"),
        ).groupedByDosePeriod()

        assertEquals(
            listOf(
                DosePeriodGroup.MORNING,
                DosePeriodGroup.EVENING,
                DosePeriodGroup.BEDTIME,
                DosePeriodGroup.WITHOUT_PERIOD,
            ),
            groups.map { it.group },
        )
    }

    @Test
    fun `input order is preserved inside every group`() {
        val input = listOf(med(3, "afterBreakfast"), med(1, "morning"), med(2, "afterBreakfast,afterLunch"))

        val groups = input.groupedByDosePeriod()

        assertEquals(listOf(3L, 1L, 2L), groups.idsOf(DosePeriodGroup.MORNING))
        assertEquals(listOf(2L), groups.idsOf(DosePeriodGroup.NOON))
    }

    @Test
    fun `grouping neither drops nor invents a medication and reuses the same instances`() {
        val input = listOf(med(1, "afterBreakfast"), med(2, "afterBreakfast,afterDinner"), med(3, "exact"))

        val rows = input.groupedByDosePeriod().rows()

        assertEquals(input.map { it.id }.toSet(), rows.map { it.id }.toSet())
        input.forEach { original ->
            rows.filter { it.id == original.id }.forEach { row ->
                assertSame("行必须是同一个实体，而不是组内副本", original, row)
            }
        }
    }

    @Test
    fun `a medication shown in two groups is one selection not two`() {
        val multi = med(7, "afterBreakfast,afterDinner")
        val groups = listOf(multi).groupedByDosePeriod()
        assertEquals("前置条件：这一行确实渲染了两次", 2, groups.rows().count { it.id == 7L })

        val selected = toggledSelection(emptySet(), 7L)

        assertEquals(setOf(7L), selected)
        assertEquals("计数按药品算，不按行算", 1, selected.size)
        // 两个组里的勾选状态来自同一个 id 集合，因此必然一致
        assertTrue(groups.rows().all { it.id in selected })
        // 任一组里取消，所有组一起取消
        assertEquals(emptySet<Long>(), toggledSelection(selected, 7L))
    }

    @Test
    fun `switching the view neither changes the filtered set nor the selection`() {
        val activeMulti = med(1, "afterBreakfast,afterDinner")
        val activeExact = med(2, "exact")
        val archivedMulti = med(3, "afterBreakfast,afterDinner", archived = true)
        val all = listOf(activeMulti, activeExact, archivedMulti)

        // 视图切换前后的唯一变化：全部视图逐个渲染 filtered，时段视图渲染 grouped(filtered)
        val filtered = all.filter { !it.isArchived }
        val selected = toggledSelection(emptySet(), activeMulti.id)

        fun checkedIds(view: List<Medication>) = view.filter { it.id in selected }.map { it.id }.distinct().toSet()

        val groupedView = filtered.groupedByDosePeriod().rows()

        assertEquals(
            "组内不得混入已停用的药",
            emptySet<Long>(),
            groupedView.map {
                it.id
            }.toSet() - filtered.map { it.id }.toSet(),
        )
        assertEquals("分组不丢药不造药", filtered.map { it.id }.toSet(), groupedView.map { it.id }.toSet())
        assertEquals("切换视图后选中的仍是同一批药品", checkedIds(filtered), checkedIds(groupedView))
        assertEquals(setOf(1L), checkedIds(groupedView))
    }

    @Test
    fun `the archived filter keeps working inside the groups`() {
        val active = med(1, "afterBreakfast")
        val archived = med(2, "afterBreakfast", archived = true)

        val activeGroups = listOf(active, archived).filter { !it.isArchived }.groupedByDosePeriod()
        val archivedGroups = listOf(active, archived).filter { it.isArchived }.groupedByDosePeriod()

        assertEquals(listOf(1L), activeGroups.idsOf(DosePeriodGroup.MORNING))
        assertEquals(listOf(2L), archivedGroups.idsOf(DosePeriodGroup.MORNING))
    }
}
