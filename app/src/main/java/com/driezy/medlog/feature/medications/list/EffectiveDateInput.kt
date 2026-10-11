package com.driezy.medlog.feature.medications.list

import java.time.LocalDate

/**
 * 批量生效日输入的纯校验（docs/medication-status-spec.md D11 / §9.5）。
 *
 * 首期**只接受今天与过去**（按成员提醒时区的本地日界）：未来日与不可解析的文本一律返回 `null`，
 * 使「确认」按钮不可点。这只是 UI 层的第一道限制；用例 `SetMedicationStatusUseCase` 仍会以
 * `FutureEffectiveDateNotAllowed` 拒绝未来日（真正的强制点）。
 */
internal object EffectiveDateInput {
    fun parse(text: String, today: LocalDate): LocalDate? =
        runCatching { LocalDate.parse(text.trim()) }.getOrNull()?.takeIf { !it.isAfter(today) }
}
