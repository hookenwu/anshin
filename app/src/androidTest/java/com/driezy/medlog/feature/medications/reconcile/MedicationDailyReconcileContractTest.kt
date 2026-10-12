package com.driezy.medlog.feature.medications.reconcile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.R
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 核对页 Compose 契约测试（T4/T7）——docs/medication-daily-reconcile.md §10。
 * 渲染 `internal …Content` + 状态对象，断言派发的动作。
 */
@RunWith(AndroidJUnit4::class)
class MedicationDailyReconcileContractTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun line(id: Long, name: String, checked: Boolean, active: Boolean, legacy: Boolean = false) =
        ReconcileLine(
            id = id,
            name = name,
            checked = checked,
            currentlyActive = active,
            liveKind = if (legacy) MedicationStatusKind.LEGACY_BASELINE else null,
            legacyUnknown = legacy,
        )

    private fun render(
        state: MedicationReconcileState,
        onToggle: (Long) -> Unit = {},
        onReasonChange: (String) -> Unit = {},
        onNext: () -> Unit = {},
        onConfirm: () -> Unit = {},
        onBack: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        compose.setContent {
            MedLogTheme(dynamicColor = false) {
                MedicationDailyReconcileContent(
                    state = state,
                    onToggle = onToggle,
                    onReasonChange = onReasonChange,
                    onNext = onNext,
                    onConfirm = onConfirm,
                    onBack = onBack,
                    onDone = onDone,
                )
            }
        }
    }

    @Test
    fun precheckDefaultsAndLegacyBadgeAreShown() {
        render(
            MedicationReconcileState(
                loading = false,
                lines = listOf(
                    line(1L, "Medicine A", checked = true, active = true),
                    line(2L, "Medicine B", checked = false, active = false),
                    line(3L, "Legacy C", checked = false, active = false, legacy = true),
                ),
            ),
        )
        compose.onNodeWithText("Medicine A").assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.medication_reconcile_legacy_unknown_badge),
        ).assertIsDisplayed()
    }

    @Test
    fun nextStepDispatchesAndTheSummaryIsShown() {
        var next = 0
        render(
            MedicationReconcileState(
                loading = false,
                lines = listOf(line(1L, "Medicine A", checked = true, active = true)),
            ),
            onNext = { next++ },
        )
        compose.onNodeWithText(context.getString(R.string.medication_reconcile_step_review)).performClick()
        compose.runOnIdle { assertEquals(1, next) }
    }

    @Test
    fun emptyReasonFieldAndDisclaimerAreShown() {
        render(
            MedicationReconcileState(
                loading = false,
                lines = listOf(line(1L, "Medicine A", checked = true, active = true)),
                reason = "",
            ),
        )
        compose.onNodeWithText(context.getString(R.string.medication_reconcile_reason)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.medication_reconcile_disclaimer)).assertIsDisplayed()
    }

    @Test
    fun allPausedGuardOpensASecondDialogDefaultingToCancel() {
        var confirm = 0
        render(
            MedicationReconcileState(
                loading = false,
                lines = listOf(
                    line(1L, "A", checked = false, active = true),
                    line(2L, "B", checked = false, active = true),
                    line(3L, "C", checked = false, active = true),
                ),
                summary = ReconcileSummary(
                    toPause = listOf(1L, 2L, 3L),
                    toResume = emptyList(),
                    unchangedCount = 0,
                    effectiveDay = LocalDate.of(2026, 9, 19),
                    needsSecondConfirmation = true,
                ),
            ),
            onConfirm = { confirm++ },
        )

        // 一次点击不足以提交：先弹出独立二次确认（默认取消）
        compose.onNodeWithText(context.getString(R.string.medication_reconcile_confirm)).performClick()
        compose.onNodeWithText(context.getString(R.string.medication_reconcile_cancel)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("默认不提交", 0, confirm) }

        compose.onNodeWithText(context.getString(R.string.medication_reconcile_cancel)).performClick()
        compose.runOnIdle { assertEquals("取消 ⇒ 零派发", 0, confirm) }
    }

    @Test
    fun staleSummaryRendersTheRebuiltNotice() {
        render(
            MedicationReconcileState(
                loading = false,
                step = ReconcileStep.SUMMARY,
                stale = true,
                lines = listOf(line(1L, "A", checked = true, active = true)),
                summary = ReconcileSummary(
                    toPause = emptyList(),
                    toResume = emptyList(),
                    unchangedCount = 1,
                    effectiveDay = LocalDate.of(2026, 9, 19),
                    needsSecondConfirmation = false,
                ),
            ),
        )
        compose.onNodeWithText(
            context.getString(R.string.medication_reconcile_stale_summary_rebuilt),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.medication_reconcile_summary_no_changes, 1),
        ).assertIsDisplayed()
    }
}
