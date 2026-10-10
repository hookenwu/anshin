package com.driezy.medlog.capability.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.toDomainSchedule
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.data.repository.reminderZone
import com.driezy.medlog.di.ApplicationScope
import com.driezy.medlog.domain.ReminderOccurrence
import com.driezy.medlog.domain.ReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_REMINDER_SLOTS = 20
private const val ALARM_PROJECTION_PREFERENCES = "alarm_projection_registry"
private const val REGISTERED_MEDICATION_IDS = "registered_medication_ids"

/** PendingIntent requestCode 偏移：提前预告闹钟用，避免与正式提醒冲突 */
const val EARLY_REMINDER_CODE_OFFSET = 50_000

/** PendingIntent requestCode 偏移：漏服再提醒闹钟 */
const val FOLLOW_UP_CODE_OFFSET = 100_000

/**
 * 闹钟调度器。
 *
 * **单一职责**：管理所有服药提醒闹钟的调度与取消。
 * 不涉及任何通知 UI 内容 —— 见 [NotificationHelper]。
 *
 * 依赖注入（Hilt）：[Singleton]，整个 App 生命周期内唯一实例。
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefsRepository: UserPreferencesRepository,
    private val careRecipients: com.driezy.medlog.data.repository.CareRecipientRepository,
    private val reminderPlanner: ReminderPlanner,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val projectionRegistry = context.getSharedPreferences(ALARM_PROJECTION_PREFERENCES, Context.MODE_PRIVATE)

    /**
     * 各成员的提醒时区与显示名（成员级作息/时区）。
     *
     * 成员维度的值不能只缓存"当前成员"一个：重排要为所有成员排闹钟。
     * [activeRecipientZoneId] 由 DataStore 实时同步（当前成员），
     * 全量缓存由 [refreshRecipientCaches] 在每次重排前填满。
     */
    private val zoneByRecipient = java.util.concurrent.ConcurrentHashMap<Long, ZoneId>()
    private val nameByRecipient = java.util.concurrent.ConcurrentHashMap<Long, String>()

    @Volatile private var activeRecipientZoneId: ZoneId = clock.zone

    /** 当前成员在缓存里不可用时的兜底时区（例如尚未选成员）。 */
    private fun zoneFor(recipientId: Long): ZoneId = zoneByRecipient[recipientId] ?: activeRecipientZoneId

    private fun nameFor(recipientId: Long): String? = nameByRecipient[recipientId]

    /** 重排前刷新各成员的时区与显示名缓存（时区用于排期，显示名用于通知标题）。 */
    suspend fun refreshRecipientCaches() {
        careRecipients.getRecipients().forEach { recipient ->
            zoneByRecipient[recipient.id] = prefsRepository.reminderZoneFor(recipient.id, clock.zone)
            nameByRecipient[recipient.id] = recipient.displayName
        }
    }

    /** 提前预告提醒分钟数（0 = 关闭），由 DataStore 实时同步 */
    @Volatile private var earlyReminderMinutes: Int = 0

    init {
        // 监听旅行模式 / 家乡时区变化
        scope.launch {
            combine(
                prefsRepository.settingsFlow,
                careRecipients.observeActiveRecipientId(),
            ) { prefs, recipientId -> recipientId to prefs }
                .collect { (recipientId, prefs) ->
                    // settingsFlow 已按成员解析，这里缓存到该成员名下，切换成员不会串档
                    if (recipientId != com.driezy.medlog.data.recipient.ActiveRecipientStore.NO_RECIPIENT) {
                        val zone = prefs.reminderZone(clock.zone)
                        zoneByRecipient[recipientId] = zone
                        activeRecipientZoneId = zone
                    } else {
                        activeRecipientZoneId = clock.zone
                    }
                    earlyReminderMinutes = prefs.earlyReminderMinutes
                }
        }
    }

    // ─── 调度 ──────────────────────────────────────────────────────────────

    /**
     * 根据药品配置为每个时间槽调度下一次提醒闹钟。
     * PRN（按需服用）药品直接跳过。
     *
     * 间隔给药（[Medication.intervalHours] > 0）：
     *   以最后实际服用时间或计划开始时间为锚点，跳过已经处理的剂量。
     */
    fun scheduleAllReminders(medication: Medication, lastTakenMs: Long? = null, handled: Set<Instant> = emptySet()) {
        reminderPlanner.nextOccurrences(
            schedule = medication.toDomainSchedule(),
            endAt = medication.endDate?.let(Instant::ofEpochMilli),
            zoneId = zoneFor(medication.careRecipientId),
            lastTakenAt = lastTakenMs?.let(Instant::ofEpochMilli),
            startAt = Instant.ofEpochMilli(medication.startDate),
            handled = handled,
        ).forEach { occurrence ->
            val triggerMs = occurrence.scheduledAt.toEpochMilli()
            scheduleAlarmSlot(medication, occurrence.slotIndex, triggerMs)
            scheduleEarlyReminderIfNeeded(medication, occurrence.slotIndex, triggerMs)
        }
    }

    /**
     * 根据照护事项配置为每个时间槽调度下一次提醒闹钟（与用药 [scheduleAllReminders] 同构）。
     *
     * 同一套 [ReminderPlanner]：`AS_NEEDED` 不产生时刻；`INTERVAL`（完成后计时）以
     * [lastDoneMs]（该事项最后一条 `DONE` 的实际完成时刻）为锚点算下一次；
     * 固定时刻沿用 [handledSlots] 跳过已处理的时间槽。
     *
     * requestCode 走 [ReminderTarget] 的照护事项编号空间（`CARE_TASK_CODE_BASE + id*100 + slot`），
     * 与用药不撞码。**提前预告与漏服再提醒本期只服务用药**，照护事项只排正点提醒。
     */
    fun scheduleCareTaskReminders(task: CareTask, lastDoneMs: Long? = null, handledSlots: Set<Instant> = emptySet()) {
        careTaskReminderOccurrences(
            planner = reminderPlanner,
            task = task,
            lastDoneMs = lastDoneMs,
            handledSlots = handledSlots,
            zoneId = zoneFor(task.careRecipientId),
        ).forEach { occurrence ->
            scheduleCareTaskAlarmSlot(task, occurrence.slotIndex, occurrence.scheduledAt.toEpochMilli())
        }
    }

    /** 调度指定照护事项时间槽的单个闹钟。intent extras 与接收器的照护事项通路对齐。 */
    private fun scheduleCareTaskAlarmSlot(task: CareTask, timeIndex: Int, triggerAtMs: Long) {
        val target = careTaskTarget(task)
        registerProjection(target)
        val intent = PendingIntent.getBroadcast(
            context,
            target.slotRequestCode(timeIndex),
            Intent(context, MedLogAlarmReceiver::class.java).apply {
                putExtra(EXTRA_TARGET_TYPE, ReminderTargetType.CARE_TASK.key)
                putExtra(EXTRA_TARGET_ID, task.id)
                putExtra(EXTRA_RECIPIENT_NAME, nameFor(task.careRecipientId))
                putExtra(EXTRA_TIME_INDEX, timeIndex)
                putExtra(EXTRA_SCHEDULED_MS, triggerAtMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleExact(intent, triggerAtMs)
    }

    /**
     * 调度指定时间槽的单个闹钟。
     * 供 [scheduleAllReminders] 内部调用，以及 [com.driezy.medlog.capability.reminders.MedLogAlarmReceiver]
     * 在每次触发后调度下一次时使用。
     */
    fun scheduleAlarmSlot(medication: Medication, timeIndex: Int, triggerAtMs: Long) {
        registerProjection(medicationTarget(medication))
        val requestCode = (medication.id * 100 + timeIndex).toInt()
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MedLogAlarmReceiver::class.java).apply {
                putExtra(EXTRA_MED_ID, medication.id)
                putExtra(EXTRA_TARGET_TYPE, ReminderTargetType.MEDICATION.key)
                putExtra(EXTRA_TARGET_ID, medication.id)
                putExtra(EXTRA_MED_NAME, medication.name)
                putExtra(EXTRA_RECIPIENT_NAME, nameFor(medication.careRecipientId))
                putExtra(EXTRA_TIME_INDEX, timeIndex)
                putExtra(EXTRA_SCHEDULED_MS, triggerAtMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleExact(intent, triggerAtMs)
    }

    /**
     * 完成一个剂量后，只推进该时间槽的下一轮提醒。
     *
     * 固定时钟计划以本次计划时间为下界，避免用户提前服药后又收到同一剂提醒；
     * 间隔给药则从实际服药时间（若有）重新计算间隔。
     */
    fun scheduleNextReminderAfterDose(
        medication: Medication,
        timeIndex: Int,
        scheduledTimeMs: Long,
        actualTakenTimeMs: Long? = null,
    ) {
        val afterMs = actualTakenTimeMs ?: maxOf(scheduledTimeMs, clock.millis())
        val triggerMs = reminderPlanner.nextOccurrenceForSlot(
            schedule = medication.toDomainSchedule(),
            slotIndex = timeIndex,
            after = Instant.ofEpochMilli(afterMs),
            endAt = medication.endDate?.let(Instant::ofEpochMilli),
            zoneId = zoneFor(medication.careRecipientId),
            startAt = Instant.ofEpochMilli(medication.startDate),
            lastTakenAt = actualTakenTimeMs?.let(Instant::ofEpochMilli),
        )?.scheduledAt?.toEpochMilli() ?: return
        scheduleAlarmSlot(medication, timeIndex, triggerMs)
        scheduleEarlyReminderIfNeeded(medication, timeIndex, triggerMs)
    }

    // ─── 取消 ──────────────────────────────────────────────────────────────

    /** 只取消指定时间槽的漏服再提醒闹钟。 */
    fun cancelFollowUpAlarm(medicationId: Long, timeIndex: Int) {
        cancelPendingAlarm(
            (medicationId * 100 + timeIndex).toInt() + FOLLOW_UP_CODE_OFFSET,
        )
    }

    /**
     * 取消某药品的所有时间槽闹钟（不影响通知 UI）。
     * 通知的取消由 [NotificationHelper.cancelAllReminderNotifications] 负责。
     */
    fun cancelAllAlarms(medicationId: Long, recipientId: Long) {
        for (i in 0 until MAX_REMINDER_SLOTS) {
            val requestCode = (medicationId * 100 + i).toInt()
            val intent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, MedLogAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            alarmManager.cancel(intent)
            intent.cancel()
        }
        // 一并取消所有提前预告闹钟
        cancelEarlyReminderAlarms(medicationId)
        // 一并取消漏服再提醒闹钟
        cancelFollowUpAlarms(medicationId)
        unregisterProjection(ReminderTarget(recipientId, ReminderTargetType.MEDICATION, medicationId))
    }

    /**
     * 取消某照护事项的所有时间槽闹钟（不影响通知 UI）。
     * 与用药版不同：提前预告与漏服再提醒本期不服务照护事项，因此只清正点槽位。
     * 通知的取消由 [NotificationHelper.cancelCareTaskNotifications] 负责。
     */
    fun cancelCareTaskAlarms(taskId: Long, recipientId: Long) {
        val target = ReminderTarget(recipientId, ReminderTargetType.CARE_TASK, taskId)
        for (i in 0 until MAX_REMINDER_SLOTS) {
            cancelPendingAlarm(target.slotRequestCode(i))
        }
        unregisterProjection(target)
    }

    /**
     * 只取消该成员已登记的闹钟，返回被取消的目标（调用方据此按类型清理通知）。
     *
     * 阶段 1 的关键改动：重排一位成员不再清掉其他成员的闹钟。
     * 旧格式登记项（阶段 1 的 `<成员>:<药品>`、阶段 0 的裸 id）一并识别，不会漏掉残留闹钟。
     */
    @Synchronized
    fun cancelAlarmsFor(recipientId: Long): List<ReminderTarget> {
        val owned = registeredTargets().filter { it.recipientId == recipientId }
        owned.forEach(::cancelTargetAlarms)
        return owned
    }

    /**
     * 清掉旧格式（阶段 0 写入的、不带成员前缀）登记项及其闹钟。
     * 这些条目无法判定归属，且会被紧随其后的重排重新登记，因此一次性作废是安全的。
     */
    @Synchronized
    fun cancelUnattributedAlarms(): List<ReminderTarget> {
        val unattributed = registeredTargets()
            .filter { it.recipientId == ActiveRecipientStore.NO_RECIPIENT }
        unattributed.forEach(::cancelTargetAlarms)
        return unattributed
    }

    /** 全量清理（删除/导入/恢复等场景）：返回被取消的目标。 */
    @Synchronized
    fun cancelAllKnownAlarms(): List<ReminderTarget> {
        val targets = registeredTargets()
        targets.forEach(::cancelTargetAlarms)
        return targets
    }

    /** 当前登记的全部目标；旧格式（阶段 1 的两段式、阶段 0 的裸 id）一并识别。 */
    @Synchronized
    private fun registeredTargets(): List<ReminderTarget> = AlarmProjectionRegistry.parseAll(
        projectionRegistry.getStringSet(REGISTERED_MEDICATION_IDS, emptySet()).orEmpty(),
    )

    /**
     * 目标所属行已不存在（例如刚被硬删除），成员 id 无从得知：
     * 按 `(type, id)` 跨成员取消闹钟并撤销所有匹配的登记项。
     *
     * 这是修复 phantom 登记项的关键：删除后 `getXxxById` 返回 null，调用方拿不到 `careRecipientId`，
     * 只按 `(0, id)` 撤销就永远清不掉真实的 `<recipientId>:<type>:<id>`。
     */
    @Synchronized
    fun cancelAlarmsForMissingOwner(type: ReminderTargetType, id: Long): List<ReminderTarget> {
        val matched = AlarmProjectionRegistry.matchingIdentity(
            projectionRegistry.getStringSet(REGISTERED_MEDICATION_IDS, emptySet()).orEmpty(),
            type,
            id,
        )
        matched.forEach(::cancelTargetAlarms)
        return matched
    }

    /**
     * 自愈清理：撤销所有所属成员/实体已消失或已归档的登记项，并取消其闹钟。
     *
     * 用于重排收尾——不依赖每条变更路径都正确，兜住成员删除、实体删除等遗漏；[isLive] 为 true 的
     * 目标原样保留，因此不会取消应当保留的闹钟。
     */
    @Synchronized
    fun pruneOrphanedProjections(isLive: (ReminderTarget) -> Boolean): List<ReminderTarget> {
        val orphans = AlarmProjectionRegistry.orphaned(
            projectionRegistry.getStringSet(REGISTERED_MEDICATION_IDS, emptySet()).orEmpty(),
            isLive,
        )
        orphans.forEach(::cancelTargetAlarms)
        return orphans
    }

    /** 取消某个目标的闹钟并撤销登记。 */
    private fun cancelTargetAlarms(target: ReminderTarget) {
        when (target.type) {
            ReminderTargetType.MEDICATION -> cancelAllAlarms(target.id, target.recipientId)
            ReminderTargetType.CARE_TASK -> cancelCareTaskAlarms(target.id, target.recipientId)
            // 照护事件由 worker 驱动、不排任何闹钟，因此没有可取消的闹钟。
            ReminderTargetType.CARE_EVENT -> Unit
        }
    }

    /** 药品的提醒目标。 */
    private fun medicationTarget(medication: Medication) =
        ReminderTarget(medication.careRecipientId, ReminderTargetType.MEDICATION, medication.id)

    /** 照护事项的提醒目标。 */
    private fun careTaskTarget(task: CareTask) =
        ReminderTarget(task.careRecipientId, ReminderTargetType.CARE_TASK, task.id)

    /**
     * 取消某药品的所有提前预告闹钟。
     * 内部用于 [cancelAllAlarms]，也可独立调用。
     */
    fun cancelEarlyReminderAlarms(medicationId: Long) {
        for (i in 0 until MAX_REMINDER_SLOTS) {
            val requestCode = (medicationId * 100 + i).toInt() + EARLY_REMINDER_CODE_OFFSET
            val intent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, MedLogAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            alarmManager.cancel(intent)
            intent.cancel()
        }
    }

    /**
     * 取消某药品的所有漏服再提醒闹钟。
     */
    fun cancelFollowUpAlarms(medicationId: Long) {
        for (i in 0 until MAX_REMINDER_SLOTS) {
            val requestCode = (medicationId * 100 + i).toInt() + FOLLOW_UP_CODE_OFFSET
            val intent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, MedLogAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            alarmManager.cancel(intent)
            intent.cancel()
        }
    }

    private fun cancelPendingAlarm(requestCode: Int) {
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MedLogAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(intent)
        intent.cancel()
    }

    @Synchronized
    private fun registerProjection(target: ReminderTarget) {
        val current = projectionRegistry.getStringSet(REGISTERED_MEDICATION_IDS, emptySet()).orEmpty()
        val updated = AlarmProjectionRegistry.register(current, target)
        if (updated != current) {
            projectionRegistry.edit { putStringSet(REGISTERED_MEDICATION_IDS, updated) }
        }
    }

    @Synchronized
    private fun unregisterProjection(target: ReminderTarget) {
        val current = projectionRegistry.getStringSet(REGISTERED_MEDICATION_IDS, emptySet()).orEmpty()
        // 本版三段式登记项（含照护事项的 `<recipientId>:task:<id>`）对所有目标都清掉；
        // 阶段 1 两段式与阶段 0 裸 id 只可能是用药，只对 MEDICATION 目标清理——
        // 否则取消同号的照护事项会误删该成员名下的药物旧登记项。
        // MEDICATION 分支与改造前逐字一致（含短路顺序）。
        val updated = AlarmProjectionRegistry.unregister(current, target)
        if (updated != current) {
            projectionRegistry.edit { putStringSet(REGISTERED_MEDICATION_IDS, updated) }
        }
    }

    /**
     * 调度漏服再提醒闹钟。
     *
     * @param medication 药品对象
     * @param timeIndex  提醒时间槽索引
     * @param scheduledMs 原始闹钟触发时间（用于对照日志查询）
     * @param followUpCount 当前是第几次再提醒（从 1 开始）
     * @param followUpMaxCount 最大再提醒次数
     * @param delayMs 再提醒间隔毫秒时
     * @param triggerAtMs 本次闹钟触发时间
     */
    fun scheduleFollowUpAlarm(
        medication: Medication,
        timeIndex: Int,
        scheduledMs: Long,
        followUpCount: Int,
        followUpMaxCount: Int,
        delayMs: Long,
        triggerAtMs: Long,
    ) {
        registerProjection(medicationTarget(medication))
        val requestCode = (medication.id * 100 + timeIndex).toInt() + FOLLOW_UP_CODE_OFFSET
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MedLogAlarmReceiver::class.java).apply {
                putExtra(EXTRA_MED_ID, medication.id)
                putExtra(EXTRA_TARGET_TYPE, ReminderTargetType.MEDICATION.key)
                putExtra(EXTRA_TARGET_ID, medication.id)
                putExtra(EXTRA_MED_NAME, medication.name)
                putExtra(EXTRA_RECIPIENT_NAME, nameFor(medication.careRecipientId))
                putExtra(EXTRA_TIME_INDEX, timeIndex)
                putExtra(EXTRA_IS_FOLLOW_UP, true)
                putExtra(EXTRA_FOLLOW_UP_COUNT, followUpCount)
                putExtra(EXTRA_FOLLOW_UP_MAX_COUNT, followUpMaxCount)
                putExtra(EXTRA_FOLLOW_UP_DELAY_MS, delayMs)
                putExtra(EXTRA_SCHEDULED_MS, scheduledMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleExact(intent, triggerAtMs)
    }

    // ─── 私有辅助 ─────────────────────────────────────────────────────────

    /**
     * 若用户开启了「提前 N 分钟预告」，则为指定时间槽调度一个提前预告闹钟。
     * 如果 [mainTriggerMs] 减去偏移后已过去或不足 1 分钟，则跳过。
     */
    private fun scheduleEarlyReminderIfNeeded(medication: Medication, timeIndex: Int, mainTriggerMs: Long) {
        val mins = earlyReminderMinutes
        if (mins <= 0) return
        val earlyTriggerMs = mainTriggerMs - mins * 60_000L
        if (earlyTriggerMs <= clock.millis() + 60_000L) return // 时机已过
        val requestCode = (medication.id * 100 + timeIndex).toInt() + EARLY_REMINDER_CODE_OFFSET
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MedLogAlarmReceiver::class.java).apply {
                putExtra(EXTRA_MED_ID, medication.id)
                putExtra(EXTRA_TARGET_TYPE, ReminderTargetType.MEDICATION.key)
                putExtra(EXTRA_TARGET_ID, medication.id)
                putExtra(EXTRA_MED_NAME, medication.name)
                putExtra(EXTRA_RECIPIENT_NAME, nameFor(medication.careRecipientId))
                putExtra(EXTRA_TIME_INDEX, timeIndex)
                putExtra(EXTRA_IS_EARLY, true)
                putExtra("early_minutes", mins)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleExact(intent, earlyTriggerMs)
    }

    /** 调度一次性精确闹钟（compat：Android 12+需要精确闹钟权限）*/
    private fun scheduleExact(intent: PendingIntent, triggerAtMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, intent)
        }
    }
}

/**
 * 照护事项下一批提醒时刻（纯计算，便于 JVM 单测）。
 *
 * 与用药 [AlarmScheduler.scheduleAllReminders] 调用同一个 [ReminderPlanner]，参数一一对应：
 * `lastTakenAt` = 该事项最后一条 `DONE` 的 `actualEndMs`（照护事项的完成锚点），
 * `startAt` = 事项起始时刻，`handled` = 已有记录的时间槽。
 */
internal fun careTaskReminderOccurrences(
    planner: ReminderPlanner,
    task: CareTask,
    lastDoneMs: Long?,
    handledSlots: Set<Instant>,
    zoneId: ZoneId,
): List<ReminderOccurrence> = planner.nextOccurrences(
    schedule = task.toDomainSchedule(),
    endAt = task.endDate?.let(Instant::ofEpochMilli),
    zoneId = zoneId,
    lastTakenAt = lastDoneMs?.let(Instant::ofEpochMilli),
    startAt = Instant.ofEpochMilli(task.startDate),
    handled = handledSlots,
)
