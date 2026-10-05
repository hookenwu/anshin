package com.driezy.medlog.feature.caretasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.driezy.medlog.R
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskCategory
import com.driezy.medlog.data.model.CareTaskCompletionMode
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.ui.util.timePeriodsLabel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** 分类值（实体存中文常量）→ 本地化标签。 */
@Composable
internal fun careTaskCategoryLabel(category: String): String = when (category) {
    CareTaskCategory.RESPIRATORY -> stringResource(R.string.care_task_category_respiratory)
    CareTaskCategory.MOBILITY -> stringResource(R.string.care_task_category_mobility)
    CareTaskCategory.SPEECH -> stringResource(R.string.care_task_category_speech)
    else -> category.ifBlank { stringResource(R.string.care_task_category_other) }
}

/**
 * 人类可读的排期摘要（列表行与详情「计划」共用）：
 * 固定时刻给出频率 + 时间，完成后计时给出小时间隔，按需给出「按需」；时长型追加默认时长。
 */
@Composable
internal fun careTaskScheduleSummary(task: CareTask): String {
    val base = when (task.scheduleKind) {
        CareTaskScheduleKind.AS_NEEDED -> stringResource(R.string.care_task_schedule_as_needed)
        CareTaskScheduleKind.INTERVAL -> stringResource(
            R.string.care_task_every_hours,
            task.intervalHours.coerceAtLeast(1),
        )
        CareTaskScheduleKind.FIXED_TIMES -> {
            val times = fixedTimesLabel(task)
            "${careTaskFrequencyLabel(task)} · $times"
        }
    }
    return if (task.completionMode == CareTaskCompletionMode.DURATION) {
        val minutes = task.defaultDurationMinutes ?: 0
        "$base · ${stringResource(R.string.care_task_duration_minutes, minutes)}"
    } else {
        base
    }
}

@Composable
private fun careTaskFrequencyLabel(task: CareTask): String = when (task.frequencyType.lowercase()) {
    "interval" -> stringResource(R.string.care_task_freq_every_days, task.frequencyInterval.coerceAtLeast(1))
    "specific_days" -> weekdayLabels(task.frequencyDays)
    else -> stringResource(R.string.care_task_freq_daily)
}

@Composable
private fun fixedTimesLabel(task: CareTask): String {
    timePeriodsLabel(task.timePeriods)?.let { return it }
    val times = task.reminderTimes.split(",").map(String::trim).filter(String::isNotEmpty)
    return times.joinToString("/").ifEmpty { "—" }
}

/** 周几标签：只按值（1=周一…7=周日）取本地化短名，不做 stringResource 拼装。 */
private fun weekdayLabels(raw: String): String {
    val days = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
    if (days.isEmpty()) return ""
    val locale = Locale.getDefault()
    return days.joinToString("、") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
}

/** 今日状态标签（列表页）。 */
@Composable
internal fun careTaskTodayStatusLabel(kind: CareTaskTodayStatus.Kind): String = when (kind) {
    CareTaskTodayStatus.Kind.DONE -> stringResource(R.string.care_task_status_done)
    CareTaskTodayStatus.Kind.SKIPPED -> stringResource(R.string.care_task_status_skipped)
    CareTaskTodayStatus.Kind.IN_PROGRESS -> stringResource(R.string.care_task_status_in_progress)
    CareTaskTodayStatus.Kind.PENDING -> stringResource(R.string.care_task_status_pending)
}
