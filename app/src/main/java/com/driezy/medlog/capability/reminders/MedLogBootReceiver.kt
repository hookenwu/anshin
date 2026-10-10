package com.driezy.medlog.capability.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.driezy.medlog.capability.reminders.application.ReconcileRemindersUseCase
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.feature.careevents.reminder.CareEventReminderCheckUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MedLogBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reconcileReminders: ReconcileRemindersUseCase

    @Inject
    lateinit var careEventReminderCheck: CareEventReminderCheckUseCase

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
        ) {
            return
        }
        goAsyncSafe {
            reconcileReminders.all(ReminderReconcileReason.SYSTEM_EVENT)
            // 接收器只给照护事件提醒一次「新的重新判定机会」（不清除日标记，spec R6/R13）。
            runCatching { careEventReminderCheck() }
        }
    }
}
