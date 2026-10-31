package com.driezy.medlog.data.model

import com.driezy.medlog.domain.model.RoutineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalTime

/**
 * `Medication.gatedFor` —— 写入口的**统一闸门**（PLAN §4.2 / INV-4）。
 *
 * 钉住三件事：
 * 1. 适格行 = 校验 + 物化（超集），非适格行（精确时间 / PRN / 间隔给药）上的非 0 偏移**必须被拒**，
 *    而 `materializedFor` 单独调用会原样放过 —— 这正是闸门存在的理由；
 * 2. 非法档位（越界 / 非 15 步长 / 回绕锚点 / 跨零点）抛 `IllegalTimeOffsetException`，
 *    **不钳位、不归零**，原行逐字不变；
 * 3. 0 偏移下与 Phase 1 行为逐字相同，且没有任何读路径消费原始 `offsetMinutes`。
 */
class MedicationOffsetGateTest {

    private val schedule = RoutineSchedule()

    // ---- 适格行：校验 + 物化 -------------------------------------------------------------

    @Test
    fun `applicable row is validated then materialized`() {
        // AFTER_BREAKFAST = 08:00 + 15(anchor) = 08:15，再 +15 偏移 = 08:30
        val gated = medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 15).gatedFor(schedule)
        assertEquals("08:30", gated.reminderTimes)
        assertEquals(8, gated.reminderHour)
        assertEquals(30, gated.reminderMinute)
        assertEquals(15, gated.offsetMinutes)
    }

    @Test
    fun `gatedFor is a superset of materializedFor for applicable rows`() {
        for (offset in -60..60 step 15) {
            for (period in TimePeriod.entries.filter { it != TimePeriod.EXACT }) {
                val row = medication(listOf(period), offset = offset)
                assertEquals(
                    "period=$period offset=$offset",
                    row.materializedFor(schedule),
                    row.gatedFor(schedule),
                )
            }
        }
    }

    @Test
    fun `override is applied and written back into the row`() {
        val row = medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 60)
        val gated = row.gatedFor(schedule, offsetOverride = 0)
        assertEquals("08:15", gated.reminderTimes)
        assertEquals("重置必须落库，否则下次重算又按旧偏移冲突", 0, gated.offsetMinutes)
    }

    // ---- 非适格行：唯一会拒绝它们的地方 ---------------------------------------------------

    @Test
    fun `exact time row rejects a non zero offset`() {
        assertRejected(medication(emptyList(), offset = 15), TimeOffsetCheck.Reason.NOT_APPLICABLE)
    }

    @Test
    fun `prn row rejects a non zero offset`() {
        assertRejected(
            medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 15, isPrn = true),
            TimeOffsetCheck.Reason.NOT_APPLICABLE,
        )
    }

    @Test
    fun `interval row rejects a non zero offset`() {
        assertRejected(
            medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 15, intervalHours = 8),
            TimeOffsetCheck.Reason.NOT_APPLICABLE,
        )
    }

    @Test
    fun `materializedFor alone lets a non applicable offset through and the gate is what rejects it`() {
        // 这就是「闸门必须先校验」的证据：早退发生在 requireAllowed 之前。
        val exact = medication(emptyList(), offset = 15)
        assertSame("materializedFor 对无基线行必须原样返回（Phase 1 合同）", exact, exact.materializedFor(schedule))
        assertRejected(exact, TimeOffsetCheck.Reason.NOT_APPLICABLE)

        val prn = medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 15, isPrn = true)
        assertSame("materializedFor 对 PRN 必须原样返回（Phase 1 合同）", prn, prn.materializedFor(schedule))
        assertRejected(prn, TimeOffsetCheck.Reason.NOT_APPLICABLE)
    }

    @Test
    fun `non applicable rows with a zero offset are returned untouched`() {
        val exact = medication(emptyList())
        assertEquals(exact, exact.gatedFor(schedule))
        val prn = medication(listOf(TimePeriod.AFTER_BREAKFAST), isPrn = true)
        assertEquals(prn, prn.gatedFor(schedule))
        val interval = medication(listOf(TimePeriod.AFTER_BREAKFAST), intervalHours = 8)
        assertEquals(interval, interval.gatedFor(schedule))
    }

    @Test
    fun `a zero override clears a corrupt non applicable offset instead of leaving it behind`() {
        val corrupted = medication(emptyList(), offset = 15)
        val cleared = corrupted.gatedFor(schedule, offsetOverride = 0)
        assertEquals(0, cleared.offsetMinutes)
        assertEquals(corrupted.reminderTimes, cleared.reminderTimes)
    }

    // ---- 非法档位：拒绝，不钳位 -----------------------------------------------------------

    @Test
    fun `range and step violations are rejected with the original value`() {
        assertRejected(
            medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = 17),
            TimeOffsetCheck.Reason.NOT_MULTIPLE_OF_15,
        )
        for (offset in listOf(-75, 75, -90, 90)) {
            val check = assertRejected(
                medication(listOf(TimePeriod.AFTER_BREAKFAST), offset = offset),
                TimeOffsetCheck.Reason.OUT_OF_RANGE,
            )
            assertEquals("拒绝必须携带原档位，不得钳到 ±60", offset, check.minutes)
        }
    }

    @Test
    fun `no illegal slot is clamped to a legal one`() {
        val lateBed = schedule.copy(bed = RoutineTime(23, 30))
        for (offset in 30..60 step 15) {
            val row = medication(listOf(TimePeriod.BEDTIME), offset = offset)
            val failure = runCatching { row.gatedFor(lateBed) }
            assertTrue("+$offset 必须被拒绝", failure.isFailure)
            val check = (failure.exceptionOrNull() as IllegalTimeOffsetException).check
            assertEquals("不得钳到 +15", offset, check.minutes)
            assertEquals(TimeOffsetCheck.Reason.CROSS_MIDNIGHT, check.reason)
        }
        // +15 仍然合法：床 23:30 + 15 = 23:45
        assertEquals("23:45", medication(listOf(TimePeriod.BEDTIME), offset = 15).gatedFor(lateBed).reminderTimes)
    }

    @Test
    fun `rejection leaves the source row untouched`() {
        val row = medication(listOf(TimePeriod.BEDTIME), offset = 60)
        runCatching { row.gatedFor(schedule.copy(bed = RoutineTime(23, 30))) }
        assertEquals("08:00", row.reminderTimes)
        assertEquals(60, row.offsetMinutes)
    }

    // ---- 回绕锚点 -------------------------------------------------------------------------

    @Test
    fun `wrapped anchor rejects every non zero offset but keeps the legacy zero offset`() {
        val wrapped = schedule.copy(breakfast = RoutineTime(0, 10))
        for (offset in listOf(15, -15, 60, -60)) {
            val check = assertRejected(
                medication(listOf(TimePeriod.BEFORE_BREAKFAST), offset = offset),
                TimeOffsetCheck.Reason.WRAPPED_ANCHOR,
                schedule = wrapped,
            )
            assertEquals(RoutineAnchor.BEFORE_BREAKFAST, check.anchor)
        }
        // 0 偏移是祖父条款：渲染逐字等于旧解析
        assertEquals("23:55", medication(listOf(TimePeriod.BEFORE_BREAKFAST)).gatedFor(wrapped).reminderTimes)
    }

    // ---- 跨零点：判定在解析后的瞬时上 -----------------------------------------------------

    @Test
    fun `cross midnight is decided on the resolved instant`() {
        val lateBed = schedule.copy(bed = RoutineTime(23, 45))
        val check = assertRejected(
            medication(listOf(TimePeriod.BEDTIME), offset = 15),
            TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
            schedule = lateBed,
        )
        assertEquals("瞬时越界：23:45 + 15 = 1440", 1440, check.resolvedMinuteOfDay)
        assertEquals(LocalTime.of(23, 45), check.base)
    }

    @Test
    fun `cross midnight is also detected before zero on the resolved instant`() {
        val earlyWake = schedule.copy(wake = RoutineTime(0, 5))
        val check = assertRejected(
            medication(listOf(TimePeriod.MORNING), offset = -15),
            TimeOffsetCheck.Reason.CROSS_MIDNIGHT,
            schedule = earlyWake,
        )
        assertEquals(-10, check.resolvedMinuteOfDay)
    }

    // ---- 历史兼容：0 偏移逐字相同 ---------------------------------------------------------

    @Test
    fun `zero offset gate output is identical to phase one materialization`() {
        for (period in TimePeriod.entries) {
            val row = medication(listOf(period))
            val gated = row.gatedFor(schedule)
            if (period == TimePeriod.EXACT) {
                assertEquals(row, gated)
            } else {
                val anchor = period.toAnchorOrNull()!!
                val expected = format(schedule.resolve(anchor))
                assertEquals("$period", expected, gated.reminderTimes)
                assertEquals(expected, row.materializedFor(schedule).reminderTimes)
                assertEquals(schedule.resolve(anchor).hour, gated.reminderHour)
                assertEquals(schedule.resolve(anchor).minute, gated.reminderMinute)
            }
            assertEquals(0, gated.offsetMinutes)
        }
    }

    @Test
    fun `zero offset gate is idempotent`() {
        val once = medication(listOf(TimePeriod.BEFORE_BREAKFAST, TimePeriod.AFTER_DINNER)).gatedFor(schedule)
        assertEquals(once, once.gatedFor(schedule))
    }

    // ---- 结构断言：一处算术、读路径不看偏移 -----------------------------------------------

    @Test
    fun `the gate contains no arithmetic of its own`() {
        val source = mainSource("app/src/main/java/com/driezy/medlog/data/model/MedicationScheduleMapper.kt")
        val gateBody = source.substringAfter("fun Medication.gatedFor(").substringBefore("\n}\n")
        assertTrue("闸门只能委托 materializedFor，不得自己加偏移", "resolveWithOffset" !in gateBody)
        assertTrue("闸门不得出现加法", "+ offsetMinutes" !in gateBody && "offsetMinutes +" !in gateBody)
    }

    @Test
    fun `no read path consumes the raw offset`() {
        val readPathRoots = listOf(
            "capability/reminders",
            "capability/widgets",
            "feature/medications/application",
            "feature/medications/home",
            "feature/medications/detail",
            "feature/medications/reconcile",
            "feature/medications/list",
        )
        val appMain = repoRoot().resolve("app/src/main/java/com/driezy/medlog")
        // B11 起详情页按 §5.2 显示偏移（中性标记 + 预览）⇒ 展示性读取是允许的；
        // B12 起「我的药品」行同样只把偏移显示成文字（追加一行 `提醒 +15 分钟`）；
        // 两者都不得用偏移派生任何计划时间（下面单独断言）。
        val displayOnlyFiles = setOf("MedicationDetailScreen.kt", "MyMedicationsScreen.kt")
        val offenders = readPathRoots
            .flatMap { root ->
                appMain.resolve(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            .flatMap { file -> file.readLines().mapIndexed { index, line -> Triple(file, index + 1, line) } }
            .filter { (_, _, line) -> "offsetMinutes" in line }
            .filterNot { (file, _, _) -> file.name in displayOnlyFiles }
            .filterNot { (_, _, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
        assertTrue("读路径不得读 offsetMinutes（INV-3）：$offenders", offenders.isEmpty())

        val displaySites = mapOf(
            "详情页" to "feature/medications/detail/MedicationDetailScreen.kt",
            "我的药品行" to "feature/medications/list/MyMedicationsScreen.kt",
        )
        displaySites.forEach { (where, path) ->
            val source = appMain.resolve(path).readText()
            listOf("resolveWithOffset", "plusMinutes", "minusMinutes", "+ offsetMinutes").forEach { token ->
                assertTrue("${where}只能展示偏移，不得用它算时间：$token", token !in source)
            }
        }
    }

    @Test
    fun `the domain schedule ignores the offset column`() {
        val baseline = medication(listOf(TimePeriod.AFTER_BREAKFAST))
        val shifted = baseline.copy(offsetMinutes = 15)
        assertEquals(baseline.toDomainSchedule(), shifted.toDomainSchedule())
        assertEquals(baseline.scheduledLocalTimeForSlot(0), shifted.scheduledLocalTimeForSlot(0))
        assertEquals(baseline.earliestScheduledTime(), shifted.earliestScheduledTime())
    }

    @Test
    fun `exactly one arithmetic point remains`() {
        val occurrences = mainSources()
            .flatMap { file -> file.readLines().mapIndexed { index, line -> Triple(file, index + 1, line) } }
            .filter { (_, _, line) -> "resolveWithOffset(" in line }
            .filterNot { (_, _, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
        assertEquals("只允许定义 + 唯一调用方：$occurrences", 2, occurrences.size)
        assertEquals(1, occurrences.count { (file, _, _) -> file.name == "MedicationScheduleMapper.kt" })
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun assertRejected(
        row: Medication,
        reason: TimeOffsetCheck.Reason,
        schedule: RoutineSchedule = this.schedule,
    ): TimeOffsetCheck.Rejected {
        val failure = runCatching { row.gatedFor(schedule) }
        assertTrue("必须抛 IllegalTimeOffsetException（$reason）", failure.exceptionOrNull() is IllegalTimeOffsetException)
        val check = (failure.exceptionOrNull() as IllegalTimeOffsetException).check
        assertEquals(reason, check.reason)
        return check
    }

    private fun medication(
        periods: List<TimePeriod>,
        offset: Int = 0,
        isPrn: Boolean = false,
        intervalHours: Int = 0,
    ) = Medication(
        name = "测试药",
        doseUnit = "片",
        timePeriod = TimePeriods.encode(periods),
        reminderTimes = "08:00",
        reminderHour = 8,
        reminderMinute = 0,
        offsetMinutes = offset,
        isPRN = isPrn,
        intervalHours = intervalHours,
    )

    private fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private fun mainSource(relative: String): String = repoRoot().resolve(relative).readText()

    private fun mainSources(): List<File> = repoRoot().walkTopDown()
        .onEnter { dir -> dir.name != "build" && !dir.name.startsWith(".git") }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
        ?: error("找不到仓库根目录（settings.gradle.kts）")
}
