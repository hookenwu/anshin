package com.driezy.medlog.feature.medications.list

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 四套 locale 文案齐备（docs/medication-status-spec.md §12.2）：
 * 状态 / 暂停区间 / 「暂停不计漏服」/ 未知区间 / 批量操作 的字符串必须在
 * `values`、`values-en`、`values-ja`、`values-ko` 的 `medication_flow_strings.xml` 中都存在。
 */
class MedicationStatusStringsLocaleTest {

    private val locales = listOf("values", "values-en", "values-ja", "values-ko")

    private val required = listOf(
        "medication_status_temporary_pause",
        "medication_status_discontinue",
        "medication_status_resume",
        "medication_status_paused",
        "medication_status_archived",
        "medication_status_paused_no_missed",
        "medication_status_partial_pause",
        "medication_status_unknown",
        "medication_status_archived_since_migration",
        "medication_status_paused_excluded",
        "medication_status_unknown_excluded",
        "medication_status_adjusted",
        "medication_batch",
        "medication_batch_pause",
        "medication_batch_resume",
        "medication_batch_effective_date",
        "medication_batch_reason",
        "medication_batch_future_date_rejected",
    )

    @Test
    fun `every locale ships the paused unknown and batch strings`() {
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
}
