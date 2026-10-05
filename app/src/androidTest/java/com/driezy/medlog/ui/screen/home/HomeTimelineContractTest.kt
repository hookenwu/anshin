package com.driezy.medlog.feature.medications.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.repository.HomeHeroStyle
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * T7 今日时间轴 Compose 契约：混排顺序、筛选切换、进度口径、
 * 以及照护事项行动动作经 [HomeUiAction] 分派。渲染 `internal` 的 [HomeContent]（状态对象入参）。
 *
 * 状态固定 `autoCollapseCompletedGroups = false`，避免「全部完成即折叠」影响动作可见性断言；
 * 行内入场动画会逐项延迟，故断言前用 [awaitTag] 等待节点出现。
 */
@RunWith(AndroidJUnit4::class)
class HomeTimelineContractTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val nowMs: Long = todayAt(12)

    private fun todayAt(hour: Int): Long = LocalDate.now(zone).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    // ── 数据构造 ────────────────────────────────────────────

    private fun medication(id: Long, name: String, hour: Int, taken: Boolean): MedicationWithStatus {
        val medication = Medication(
            id = id,
            name = name,
            doseUnit = "tablet",
            reminderTimes = "%02d:00".format(hour),
        )
        val log = if (taken) {
            MedicationLog(id = id, medicationId = id, scheduledTimeMs = todayAt(hour), status = LogStatus.TAKEN)
        } else {
            null
        }
        return MedicationWithStatus(
            medication = medication,
            log = log,
            scheduledTime = "%02d:00".format(hour),
            scheduledAtMs = todayAt(hour),
        )
    }

    private fun task(
        id: Long,
        title: String,
        hour: Int,
        category: String = CareTaskCategory.RESPIRATORY,
        mode: CareTaskCompletionMode = CareTaskCompletionMode.TOGGLE,
    ) = CareTask(
        id = id,
        careRecipientId = 1L,
        title = title,
        category = category,
        completionMode = mode,
        scheduleKind = CareTaskScheduleKind.FIXED_TIMES,
        reminderTimes = "%02d:00".format(hour),
        startDate = 0L,
    )

    private fun careLog(taskId: Long, hour: Int, status: CareTaskLogStatus) = CareTaskLog(
        id = taskId,
        careTaskId = taskId,
        scheduledTimeMs = todayAt(hour),
        status = status,
    )

    private fun todayItemKey(targetType: TodayTargetType, id: Long, slot: Int, ms: Long) = "$targetType:$id:$slot:$ms"

    private fun state(
        meds: List<MedicationWithStatus>,
        tasks: List<CareTask>,
        logs: List<CareTaskLog> = emptyList(),
    ): HomeUiState = HomeUiState(
        today = LocalDate.now(zone),
        items = meds,
        isLoading = false,
        groupByTime = true,
        autoCollapseCompletedGroups = false,
        currentMinuteOfDay = 0,
        homeHeroStyle = HomeHeroStyle.ACTION,
        todayItems = mergeTodayItems(
            medicationsToTodayItems(meds),
            careTasksToTodayItems(tasks, logs, zone, nowMs),
        ),
    )

    private fun render(state: HomeUiState, onAction: (HomeUiAction) -> Unit = {}) {
        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                HomeContent(
                    uiState = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onAction = onAction,
                    onAddMedication = {},
                    onMedicationClick = {},
                    onOpenSettings = {},
                    onOpenCareTasks = {},
                )
            }
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    // ── 混排顺序 ────────────────────────────────────────────

    @Test
    fun mixedTimelineOrdersMedicationAndCareByTime() {
        val aspirin = medication(1L, "Aspirin", 8, taken = true)
        val ibuprofen = medication(2L, "Ibuprofen", 20, taken = true)
        val oxygen = task(5L, "吸氧", 9)
        val turn = task(6L, "翻身", 13, category = CareTaskCategory.MOBILITY)

        render(state(meds = listOf(aspirin, ibuprofen), tasks = listOf(oxygen, turn)))

        val aspirinTag = "todayRow:${todayItemKey(TodayTargetType.MEDICATION, 1L, 0, todayAt(8))}"
        val oxygenTag = "todayRow:${todayItemKey(TodayTargetType.CARE_TASK, 5L, 0, todayAt(9))}"
        val turnTag = "todayRow:${todayItemKey(TodayTargetType.CARE_TASK, 6L, 0, todayAt(13))}"
        val ibuprofenTag = "todayRow:${todayItemKey(TodayTargetType.MEDICATION, 2L, 0, todayAt(20))}"
        listOf(aspirinTag, oxygenTag, turnTag, ibuprofenTag).forEach(::awaitTag)

        val aspirinTop = composeRule.onNodeWithTag(aspirinTag).fetchSemanticsNode().boundsInRoot.top
        val oxygenTop = composeRule.onNodeWithTag(oxygenTag).fetchSemanticsNode().boundsInRoot.top
        val turnTop = composeRule.onNodeWithTag(turnTag).fetchSemanticsNode().boundsInRoot.top
        val ibuprofenTop = composeRule.onNodeWithTag(ibuprofenTag).fetchSemanticsNode().boundsInRoot.top

        assertTrue("08:00 用药应在最上", aspirinTop < oxygenTop)
        assertTrue("09:00 照护应在 13:00 前", oxygenTop < turnTop)
        assertTrue("13:00 照护应在 20:00 用药前", turnTop < ibuprofenTop)
    }

    // ── 筛选切换 ────────────────────────────────────────────

    @Test
    fun filterChipsSwitchBetweenMedicationAndCare() {
        val aspirin = medication(1L, "Aspirin", 8, taken = true)
        val oxygen = task(5L, "吸氧", 9)
        var uiState by mutableStateOf(state(meds = listOf(aspirin), tasks = listOf(oxygen)))

        composeRule.setContent {
            MedLogTheme(dynamicColor = false) {
                HomeContent(
                    uiState = uiState,
                    snackbarHostState = remember { SnackbarHostState() },
                    onAction = { action ->
                        if (action is HomeUiAction.SetTodayFilter) uiState = uiState.copy(todayFilter = action.filter)
                    },
                    onAddMedication = {},
                    onMedicationClick = {},
                    onOpenSettings = {},
                    onOpenCareTasks = {},
                )
            }
        }

        val careRow = "todayRow:${todayItemKey(TodayTargetType.CARE_TASK, 5L, 0, todayAt(9))}"
        val medRow = "todayRow:${todayItemKey(TodayTargetType.MEDICATION, 1L, 0, todayAt(8))}"

        awaitTag(careRow)
        composeRule.onNodeWithTag(careRow).assertIsDisplayed()
        composeRule.onNodeWithTag(medRow).assertIsDisplayed()

        composeRule.onNodeWithTag("todayFilterMedication").performClick()
        awaitGone(careRow)
        composeRule.onNodeWithTag(medRow).assertIsDisplayed()

        composeRule.onNodeWithTag("todayFilterCare").performClick()
        awaitGone(medRow)
        composeRule.onNodeWithTag(careRow).assertIsDisplayed()
    }

    // ── 进度口径 ────────────────────────────────────────────

    @Test
    fun progressCountsMedicationAndCareTogether() {
        val aspirin = medication(1L, "Aspirin", 8, taken = true)
        val ibuprofen = medication(2L, "Ibuprofen", 12, taken = false)
        val oxygen = task(5L, "吸氧", 9)

        render(
            state(
                meds = listOf(aspirin, ibuprofen),
                tasks = listOf(oxygen),
                logs = listOf(careLog(5L, 9, CareTaskLogStatus.DONE)),
            ),
        )

        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        composeRule.onNodeWithTag("homePlanCount")
            .assertTextEquals(resources.getQuantityString(R.plurals.home_hero_plan_count, 3, 3))
        composeRule.onNodeWithTag("homeTodayCount")
            .assertTextEquals(resources.getString(R.string.home_hero_today_count, 2, 3))
    }

    // ── 照护事项行动动作 ─────────────────────────────────────

    @Test
    fun careTaskRowsDispatchCompletionThroughHomeActions() {
        val actions = mutableListOf<HomeUiAction>()
        val oxygen = task(5L, "吸氧", 9)
        val duration = task(6L, "翻身", 10, mode = CareTaskCompletionMode.DURATION, category = CareTaskCategory.MOBILITY)

        render(state(meds = emptyList(), tasks = listOf(oxygen, duration)), onAction = actions::add)

        val toggleKey = todayItemKey(TodayTargetType.CARE_TASK, 5L, 0, todayAt(9))
        val durationKey = todayItemKey(TodayTargetType.CARE_TASK, 6L, 0, todayAt(10))

        awaitTag("todayCareComplete:$toggleKey")
        composeRule.onNodeWithTag("todayCareComplete:$toggleKey").performClick()
        composeRule.onNodeWithTag("todayCareSkip:$toggleKey").performClick()
        confirmTag("todayCareStart:$durationKey")
        composeRule.onNodeWithTag("todayCareStart:$durationKey").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    HomeUiAction.CareTaskComplete(5L, todayAt(9)),
                    HomeUiAction.CareTaskSkip(5L, todayAt(9)),
                    HomeUiAction.CareTaskStart(6L, todayAt(10)),
                ),
                actions,
            )
        }
    }

    @Test
    fun handledCareTaskOffersUndo() {
        val actions = mutableListOf<HomeUiAction>()
        val oxygen = task(5L, "吸氧", 9)

        render(
            state(meds = emptyList(), tasks = listOf(oxygen), logs = listOf(careLog(5L, 9, CareTaskLogStatus.DONE))),
            onAction = actions::add,
        )

        val key = todayItemKey(TodayTargetType.CARE_TASK, 5L, 0, todayAt(9))
        confirmTag("todayCareUndo:$key")
        composeRule.onNodeWithTag("todayCareUndo:$key").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(HomeUiAction.CareTaskUndo(5L, todayAt(9))), actions)
        }
    }

    private fun confirmTag(tag: String) {
        awaitTag(tag)
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }
}
