package com.driezy.medlog.feature.careevents.reminder

import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.CareEventReminderPreferences
import com.driezy.medlog.data.repository.CareEventRepository
import javax.inject.Inject

/**
 * 生产 [CareEventReminderStateSource]：读锚点经 [CareEventRepository]（当前成员作用域），
 * 读开关/阈值/日标记经 [CareEventReminderPreferences]（按 (成员, kind) 分片）。
 */
class CareEventReminderStateSourceImpl @Inject constructor(
    private val repository: CareEventRepository,
    private val preferences: CareEventReminderPreferences,
    private val activeRecipient: ActiveRecipientStore,
) : CareEventReminderStateSource {

    override suspend fun activeRecipientId(): Long = activeRecipient.current()

    override suspend fun isEnabled(recipientId: Long, kind: String): Boolean = preferences.isEnabled(recipientId, kind)

    override suspend fun thresholdDays(recipientId: Long, kind: String): Int =
        preferences.thresholdDays(recipientId, kind)

    override suspend fun newestAnchorMs(recipientId: Long, kind: String): Long? =
        repository.getNewestOnce(kind)?.occurredAtMs

    override suspend fun nudgedDay(recipientId: Long, kind: String): Long? = preferences.nudgedDay(recipientId, kind)

    override suspend fun markNudged(recipientId: Long, kind: String, epochDay: Long) =
        preferences.setNudgedDay(recipientId, kind, epochDay)
}
