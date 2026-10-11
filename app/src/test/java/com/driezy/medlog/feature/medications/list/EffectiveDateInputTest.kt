package com.driezy.medlog.feature.medications.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 批量生效日输入的纯校验（docs/medication-status-spec.md D11 / §9.5 / §16.7）。
 *
 * 首期只接受今天与过去：未来日与不可解析文本一律拒绝（返回 `null` ⇒ 确认按钮不可点）。
 * 真正的强制点是 `SetMedicationStatusUseCase`（`FutureEffectiveDateNotAllowed`），本测试只钉 UI 层第一道闸。
 */
class EffectiveDateInputTest {

    private val today = LocalDate.of(2026, 9, 19)

    @Test
    fun `today and past dates are accepted`() {
        assertEquals(today, EffectiveDateInput.parse("2026-09-19", today))
        assertEquals(today.minusDays(1), EffectiveDateInput.parse("2026-09-18", today))
        assertEquals(today.minusDays(30), EffectiveDateInput.parse("2026-08-20", today))
    }

    @Test
    fun `a future date is rejected`() {
        assertNull("明天必须被拒绝", EffectiveDateInput.parse("2026-09-20", today))
        assertNull(EffectiveDateInput.parse("2027-01-01", today))
    }

    @Test
    fun `unparseable text is rejected`() {
        assertNull(EffectiveDateInput.parse("", today))
        assertNull(EffectiveDateInput.parse("   ", today))
        assertNull(EffectiveDateInput.parse("2026/09/19", today))
        assertNull(EffectiveDateInput.parse("not-a-date", today))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(today, EffectiveDateInput.parse(" 2026-09-19 ", today))
    }
}
