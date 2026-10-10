package com.driezy.medlog.feature.careevents.reminder

import com.driezy.medlog.capability.reminders.NotificationHelper
import com.driezy.medlog.data.model.CareEventKind
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareEventReminderPreferences
import com.driezy.medlog.data.repository.CareEventRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缺席型每日提醒的**唯一入口**（docs/tracked-events-spec.md §5 R9/R11/R13）。
 *
 * 由**既有 15 分钟周期 `WidgetRefreshWorker`** 与**既有 `MedLogBootReceiver`** 调用；
 * **写入路径不调用它**——历史变更只重算派生状态、绝不即时通知（R11）。
 * 时间基准固定为**设备本地时区**（`Clock.systemDefaultZone()`），与 spec §5 说明一致。
 */
@Singleton
class CareEventReminderCheckUseCase @Inject constructor(
    private val repository: CareEventRepository,
    private val preferences: CareEventReminderPreferences,
    private val activeRecipient: ActiveRecipientStore,
    private val notificationHelper: NotificationHelper,
    private val clock: Clock,
) {
    suspend operator fun invoke(kind: String = CareEventKind.BOWEL): CareEventReminderDecision {
        val engine = CareEventReminderEngine(
            state = CareEventReminderStateSourceImpl(repository, preferences, activeRecipient),
            clock = clock,
            zoneProvider = { clock.zone },
            send = { nudge -> notificationHelper.showCareEventNotification(nudge.daysSince, nudge.recipientId) },
        )
        return engine.check(kind)
    }
}
