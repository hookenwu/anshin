package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.capability.reminders.NotificationHelper
import com.driezy.medlog.data.repository.CareRecipientRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装"今日进度通知"，将 NotificationHelper 与 HomeViewModel 解耦（SRP）。
 * HomeViewModel 只需调用 progressNotif(taken, total, pendingNames)，
 * 不再直接依赖 NotificationHelper。
 */
@Singleton
class ProgressNotificationUseCase @Inject constructor(
    private val notificationHelper: NotificationHelper,
    private val careRecipients: CareRecipientRepository,
) {
    /**
     * 今日进度通知按"当前成员"渲染并带上成员名（阶段 1）。
     * 仍复用同一个通知 id：切成员时内容被整体改写，不会留下另一位成员的常驻残留。
     */
    suspend operator fun invoke(taken: Int, total: Int, pendingNames: List<String>) {
        val memberName = careRecipients.activeRecipient()?.displayName
        notificationHelper.showOrUpdateProgressNotification(
            taken = taken,
            total = total,
            pendingNames = pendingNames,
            memberName = memberName,
        )
    }

    fun dismiss() {
        notificationHelper.dismissProgressNotification()
    }
}
