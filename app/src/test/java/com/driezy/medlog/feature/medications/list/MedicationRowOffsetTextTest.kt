package com.driezy.medlog.feature.medications.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 我的药品行内「提醒偏移」（B12）。
 *
 * 用户报的真实缺口：详情页看得到 +15，列表行里什么都没有。这里锁定三件事：
 * 1. 非零才追加（+ 与 − 各自成形）；2. 0 时**一点额外文字都不出现**；
 * 3. 符号与单位来自四个 locale 的资源，不在代码里拼死。
 */
class MedicationRowOffsetTextTest {

    private val locales = listOf("values", "values-en", "values-ja", "values-ko")

    private fun xml(locale: String): String {
        val file = File("src/main/res/$locale/medication_flow_strings.xml")
        assumeTrue("资源目录不可见，跳过：$file", file.exists())
        return file.readText()
    }

    private fun value(xml: String, key: String): String =
        Regex("""name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            ?: error("缺少字符串 $key")

    private fun segment(locale: String, minutes: Int): String? {
        val strings = xml(locale)
        return offsetRowSegment(
            minutes,
            value(strings, "medication_row_offset_later"),
            value(strings, "medication_row_offset_earlier"),
        )
    }

    @Test
    fun `a later offset renders the signed minutes and unit`() {
        assertEquals("提醒 +15 分钟", segment("values", 15))
    }

    @Test
    fun `an earlier offset renders the localized minus sign`() {
        assertEquals("提醒 −15 分钟", segment("values", -15))
    }

    @Test
    fun `a zero offset adds nothing at all`() {
        locales.forEach { locale ->
            assertNull("[$locale] 偏移为 0 时行内不得多出任何文字", segment(locale, 0))
        }
    }

    @Test
    fun `every locale ships both strings with a sign a placeholder and a unit`() {
        locales.forEach { locale ->
            val later = value(xml(locale), "medication_row_offset_later")
            val earlier = value(xml(locale), "medication_row_offset_earlier")
            assertTrue("[$locale] later 缺占位符：$later", later.contains("%1\$d"))
            assertTrue("[$locale] earlier 缺占位符：$earlier", earlier.contains("%1\$d"))
            assertTrue("[$locale] later 缺加号：$later", later.contains('+'))
            assertTrue("[$locale] earlier 缺 U+2212 减号：$earlier", earlier.contains('−'))
            // 数字后面必须有单位（而非只留一个符号 + 数字）
            assertTrue(
                "[$locale] 渲染结果缺单位：${segment(locale, 15)}",
                Regex("[+−]15\\D").containsMatchIn(segment(locale, 15).orEmpty()),
            )
            assertTrue(
                "[$locale] 渲染结果缺单位：${segment(locale, -15)}",
                Regex("[+−]15\\D").containsMatchIn(segment(locale, -15).orEmpty()),
            )
            // 绝对值入参：模板自己带符号，传负数也不该出现「++」或「−−」
            assertTrue("[$locale] 出现重复符号：${segment(locale, -15)}", !segment(locale, -15).orEmpty().contains("−−"))
        }
    }
}
