package com.driezy.medlog.feature.caretasks

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.toDomainSchedule
import com.driezy.medlog.domain.ReminderOccurrence
import com.driezy.medlog.domain.ScheduleOccurrences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 今日某事项在当前时间槽的完成态（列表页「今日状态」用）。 */
data class CareTaskTodayStatus(val kind: Kind, val scheduledTimeMs: Long) {
    enum class Kind { DONE, SKIPPED, IN_PROGRESS, PENDING }
}

/** "HH:mm" 呈现格式（本地化无关，跨 locale 统一）。 */
internal val HHMM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 当前时间槽：已到达的最近一次；若今日尚未到达任何时刻则取下一个未来时刻；无排期时为 null。
 * 列表与详情的「当前/首个」槽位判定共用此规则。
 */
fun currentOccurrence(occurrences: List<ReminderOccurrence>, nowMs: Long): ReminderOccurrence? =
    occurrences.lastOrNull { it.scheduledAt.toEpochMilli() <= nowMs } ?: occurrences.firstOrNull()

/** 今日展开的排期时刻；AS_NEEDED 没有固定时刻，返回空。 */
fun CareTask.todayOccurrences(zone: ZoneId, nowMs: Long): List<ReminderOccurrence> {
    if (scheduleKind == CareTaskScheduleKind.AS_NEEDED) return emptyList()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return ScheduleOccurrences.between(
        schedule = toDomainSchedule(),
        startAt = Instant.ofEpochMilli(startDate),
        endAt = endDate?.let { Instant.ofEpochMilli(it) },
        from = today.atStartOfDay(zone).toInstant(),
        until = today.plusDays(1).atStartOfDay(zone).toInstant(),
        zone = zone,
    )
}

/**
 * 列表页今日状态：只有 FIXED_TIMES（有明确时间槽）能廉价给出；
 * 用当日日志按 `(careTaskId, scheduledTimeMs)` 命中当前时间槽，其余排期类型返回 null。
 */
fun CareTask.todayStatus(zone: ZoneId, nowMs: Long, todayLogs: List<CareTaskLog>): CareTaskTodayStatus? {
    if (scheduleKind != CareTaskScheduleKind.FIXED_TIMES) return null
    val occurrence = currentOccurrence(todayOccurrences(zone, nowMs), nowMs) ?: return null
    val scheduledMs = occurrence.scheduledAt.toEpochMilli()
    val log = todayLogs.firstOrNull { it.careTaskId == id && it.scheduledTimeMs == scheduledMs }
    val kind = when (log?.status) {
        CareTaskLogStatus.DONE -> CareTaskTodayStatus.Kind.DONE
        CareTaskLogStatus.SKIPPED -> CareTaskTodayStatus.Kind.SKIPPED
        CareTaskLogStatus.IN_PROGRESS -> CareTaskTodayStatus.Kind.IN_PROGRESS
        null -> CareTaskTodayStatus.Kind.PENDING
    }
    return CareTaskTodayStatus(kind, scheduledMs)
}

/**
 * 归一化 "HH:mm" 输入：仅接受 24 小时制的 `H:mm` / `HH:mm`，越界或格式错误返回 null（不猜测）。
 */
fun normalizeHhmm(raw: String): String? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
}
