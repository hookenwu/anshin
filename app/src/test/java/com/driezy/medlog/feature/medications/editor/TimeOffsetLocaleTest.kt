package com.driezy.medlog.feature.medications.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B11 的文案完整性：新增 key 必须四语言齐全（values / -en / -ja / -ko），
 * 缺一个语言在 `values` 回落时用户会看到中文——这在本产品里算未完成。
 */
class TimeOffsetLocaleTest {

    private val keys = listOf(
        "time_offset_section_title",
        "time_offset_stepper_label",
        "time_offset_preview_format",
        "time_offset_disclaimer",
        "time_offset_cross_midnight_hint_later",
        "time_offset_cross_midnight_hint_earlier",
        "time_offset_illegal_stored",
        "time_offset_wrapped_anchor_hint",
        "time_offset_minutes_format",
        "editor_save_offset_rejected",
        "time_offset_startup_banner",
    )

    private val locales = listOf("values", "values-en", "values-ja", "values-ko")

    @Test
    fun `every new string exists exactly once in all four locales`() {
        locales.forEach { locale ->
            val xml = File("src/main/res/$locale/strings.xml").readText()
            keys.forEach { key ->
                val occurrences = Regex("<string name=\"$key\">").findAll(xml).count()
                assertEquals("$locale 缺 $key（或重复定义）", 1, occurrences)
            }
        }
    }

    @Test
    fun `the four locales carry the same set of offset keys`() {
        val perLocale = locales.associateWith { locale ->
            val xml = File("src/main/res/$locale/strings.xml").readText()
            Regex("<string name=\"(time_offset_[a-z_]+|editor_save_offset_rejected)\">")
                .findAll(xml)
                .map { it.groupValues[1] }
                .toSet()
        }

        assertEquals("四个语言的 key 集合必须相等", perLocale.getValue("values"), perLocale.getValue("values-en"))
        assertEquals(perLocale.getValue("values"), perLocale.getValue("values-ja"))
        assertEquals(perLocale.getValue("values"), perLocale.getValue("values-ko"))
        assertEquals(keys.toSet(), perLocale.getValue("values"))
    }

    @Test
    fun `the disclaimer keeps the follow the doctor wording in every locale`() {
        val mustContain = mapOf(
            "values" to "以医嘱为准",
            "values-en" to "follow your doctor's orders",
            "values-ja" to "医師の指示",
            "values-ko" to "의사의 지시",
        )
        mustContain.forEach { (locale, phrase) ->
            val xml = File("src/main/res/$locale/strings.xml").readText().replace("\\'", "'")
            assertTrue("$locale 的免责声明必须保留医嘱优先措辞", phrase in xml)
        }
    }

    @Test
    fun `the dedicated rejection is not the generic save failure`() {
        locales.forEach { locale ->
            val xml = File("src/main/res/$locale/strings.xml").readText()
            val line = xml.lineSequence().first { "name=\"editor_save_offset_rejected\"" in it }
            assertTrue("$locale 的专用文案必须带三个参数", "%1\$s" in line && "%2\$s" in line && "%3\$s" in line)
        }
    }
}
