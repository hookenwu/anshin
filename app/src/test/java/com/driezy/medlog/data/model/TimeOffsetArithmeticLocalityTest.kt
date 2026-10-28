package com.driezy.medlog.data.model

import com.driezy.medlog.util.ReminderTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结构断言：**全仓只有一处**把偏移与时间基线相加（docs/medication-time-offset.md §8 第 25 条）。
 *
 * 动机很具体：后续阶段会新增两条写入口（导入、恢复）。没有这条断言，下一个人很容易在其中一条里
 * 再写一遍 `+ offsetMinutes`，于是同一味药被加两次。这里不靠注释，靠在源码里数调用点。
 */
class TimeOffsetArithmeticLocalityTest {

    @Test
    fun `resolveWithOffset has exactly one definition and exactly one caller`() {
        val occurrences = mainSources()
            .flatMap { file -> file.readLines().mapIndexed { index, line -> Triple(file, index + 1, line) } }
            .filter { (_, _, line) -> "resolveWithOffset(" in line }
            .filterNot { (_, _, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }

        assertEquals(
            "只允许「定义 + 唯一调用方」两处：$occurrences",
            2,
            occurrences.size,
        )
        val definition = occurrences.single { (file, _, _) -> file.name == "RoutineTimeSlot.kt" }
        assertTrue("定义必须在 core/model 的 RoutineTimeSlot.kt", definition.first.path.contains("core/model/src/main"))
        assertTrue("定义必须是 fun 声明", definition.third.contains("fun RoutineSchedule.resolveWithOffset("))
        val caller = occurrences.single { (file, _, _) -> file.name != "RoutineTimeSlot.kt" }
        assertEquals(
            "唯一调用方必须是写路径物化点 materializedFor",
            "MedicationScheduleMapper.kt",
            caller.first.name,
        )
    }

    @Test
    fun `no main source adds the offset to a time except the single resolution point`() {
        val offenders = mainSources()
            .flatMap { file -> file.readLines().mapIndexed { index, line -> Triple(file, index + 1, line) } }
            .filter { (_, _, line) -> "+ offsetMinutes" in line || "offsetMinutes +" in line }
            .filterNot { (_, _, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
            // resolveWithOffset 自己的实现就是那一处加法，允许它存在
            .filterNot { (file, _, _) -> file.name == "RoutineTimeSlot.kt" }
        assertTrue("偏移只能在 resolveWithOffset 里相加，实际出现在：$offenders", offenders.isEmpty())
    }

    @Test
    fun `the reminder time delegate cannot take an offset parameter`() {
        val methods = ReminderTimeUtils::class.java.declaredMethods.filter { it.name == "timePeriodToReminderTime" }
        assertEquals("委托应有两个重载（SettingsPreferences / RoutineSchedule）", 2, methods.size)
        for (method in methods) {
            assertEquals("${method.name} 的签名只允许 (TimePeriod, 作息)", 2, method.parameterCount)
            assertFalse(
                "${method.name} 不得接收 Int 偏移参数",
                method.parameterTypes.any { it == Int::class.javaPrimitiveType || it == Integer::class.java },
            )
        }
    }

    /** 各模块 src/main 下的全部 Kotlin 源码（排除 build 与 .git）。 */
    private fun mainSources(): List<File> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }
            ?: error("找不到仓库根目录（settings.gradle.kts）")
        return root.walkTopDown()
            .onEnter { dir -> dir.name != "build" && !dir.name.startsWith(".git") }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()
    }
}
