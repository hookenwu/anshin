package com.driezy.medlog.feature.medications.reconcile

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 核对页四语文案齐备（T5）——docs/medication-daily-reconcile.md §3.3 / §8。
 *
 * 核对相关字符串必须在 `values`、`values-en`、`values-ja`、`values-ko` 的
 * `medication_flow_strings.xml` 中都存在；且**免责/非服药**语义在四语都不得弱化。
 */
class MedicationReconcileStringsLocaleTest {

    private val locales = listOf("values", "values-en", "values-ja", "values-ko")

    private val required = listOf(
        "medication_reconcile_entry",
        "medication_reconcile_title",
        "medication_reconcile_disclaimer",
        "medication_reconcile_reason",
        "medication_reconcile_step_review",
        "medication_reconcile_summary_title",
        "medication_reconcile_summary_changes",
        "medication_reconcile_summary_no_changes",
        "medication_reconcile_summary_effective_day",
        "medication_reconcile_summary_all_paused_warning",
        "medication_reconcile_bulk_change_warning",
        "medication_reconcile_all_paused_confirm_title",
        "medication_reconcile_all_paused_confirm_body",
        "medication_reconcile_bulk_confirm_title",
        "medication_reconcile_bulk_confirm_body",
        "medication_reconcile_confirm",
        "medication_reconcile_cancel",
        "medication_reconcile_back",
        "medication_reconcile_result_summary",
        "medication_reconcile_done",
        "medication_reconcile_failed",
        "medication_reconcile_stale_summary_rebuilt",
        "medication_reconcile_member_changed",
        "medication_reconcile_pending_hint",
        "medication_reconcile_checked_hint",
        "medication_reconcile_group_pause",
        "medication_reconcile_group_resume",
        "medication_reconcile_state_checked",
        "medication_reconcile_state_unchecked",
        "medication_reconcile_legacy_unknown_badge",
    )

    /** 免责 / 非服药语义（§3.3 文案硬约束）：四语各自的最小断言子串。 */
    private val disclaimerTokens = mapOf(
        "values" to "不记录今天是否服药",
        "values-en" to "not a dose record",
        "values-ja" to "服薬記録ではありません",
        "values-ko" to "복약 기록이 아닙니다",
    )

    @Test
    fun `every locale ships the reconcile strings`() {
        val root = File("src/main/res")
        assumeTrue("资源目录不可见，跳过", root.exists())
        locales.forEach { locale ->
            val file = File(root, "$locale/medication_flow_strings.xml")
            assertTrue("$file 缺失", file.exists())
            val text = file.readText()
            required.forEach { key ->
                assertTrue("[$locale] 缺少字符串 $key", text.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun `the disclaimer keeps its non dose meaning in all four locales`() {
        val root = File("src/main/res")
        assumeTrue("资源目录不可见，跳过", root.exists())
        locales.forEach { locale ->
            val text = File(root, "$locale/medication_flow_strings.xml").readText()
            val token = disclaimerTokens.getValue(locale)
            assertTrue("[$locale] 免责文案被弱化：缺少「$token」", text.contains(token))
        }
    }
}
