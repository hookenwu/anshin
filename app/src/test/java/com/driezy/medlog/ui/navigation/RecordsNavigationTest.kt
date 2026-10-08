package com.driezy.medlog.ui.navigation

import com.driezy.medlog.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「记录」Tab 改名与 `enableSymptomDiary` 语义守护（docs/record-center-spec.md §3 D1、§5、§6）。
 *
 * 关键约束（用户显式要求）：开关关闭时**不得出现任何身心记录面**，但**照护笔记必须仍然可达**——
 * 隐藏的只是底部 Tab；`Route.Records` 仍注册在 NavHost 中，首页「更多」菜单的「照护笔记」指向记录中心。
 */
class RecordsNavigationTest {

    private val projectRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun source(path: String) = File(projectRoot, path).readText()

    @Test
    fun `flag off hides the records tab but keeps the route registered`() {
        assertTrue(
            "开关打开时「记录」是底部 Tab",
            visibleTopLevelDestinations(true, true, true).any { it.route == Route.Records },
        )

        val flagOff =
            visibleTopLevelDestinations(
                enableSymptomDiary = false,
                enableDrugDatabase = true,
                enableHealthModule = true,
            )
        assertFalse("开关关闭时必须隐藏「记录」Tab", flagOff.any { it.route == Route.Records })
        assertEquals(4, flagOff.size)
        // 路由对象原位保留（NavHost 无条件注册），照护笔记因此仍可到达。
        assertEquals(Route.Records, TOP_LEVEL_DESTINATIONS[3].route)
    }

    @Test
    fun `the home more menu keeps a care notes entry pointing at the record centre`() {
        val app = source("app/src/main/java/com/driezy/medlog/ui/MedLogApp.kt")
        assertTrue(
            "「更多」菜单的照护笔记入口必须指向记录中心（开关关闭时仍可达）",
            app.contains("onOpenCareNotes = { navController.navigate(Route.Records) }"),
        )
        // 记录中心 route 无条件注册，不受 enableSymptomDiary 影响。
        assertTrue(app.contains("composable<Route.Records>"))
    }

    @Test
    fun `the records tab carries the renamed label resource`() {
        val records = TOP_LEVEL_DESTINATIONS.single { it.route == Route.Records }
        assertEquals(R.string.tab_records, records.labelRes)
    }
}
