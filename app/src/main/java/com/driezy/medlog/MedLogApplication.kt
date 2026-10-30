package com.driezy.medlog

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.driezy.medlog.capability.reminders.application.RoutineStartupConvergence
import com.driezy.medlog.capability.widgets.WidgetRefreshWorker
import com.driezy.medlog.domain.ReminderReconcileReason
import com.driezy.medlog.domain.ReminderReconciliationQueue
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MedLogApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var reminderReconciliationQueue: ReminderReconciliationQueue

    @Inject
    lateinit var routineStartupConvergence: RoutineStartupConvergence

    override fun onCreate() {
        super.onCreate()
        // Projections are disposable. Application startup is the final recovery net after
        // restore, upgrade, permission changes, or an earlier WorkManager enqueue failure.
        runCatching {
            reminderReconciliationQueue.enqueue(ReminderReconcileReason.SYSTEM_EVENT)
        }
        // docs/medication-time-offset.md §3.6：作息重算的启动收敛点。**只发起、不等待**——
        // 跑在 application 作用域的 supervisor 协程里，onCreate 不 runBlocking、不 await；
        // 失败被分类记录到持久标记，既不静默也不崩启动（W1/W3/W4/W5 的收敛点）。
        routineStartupConvergence.schedule()
        // 注册 15 分钟周期刷新：确保小组件数据始终与 Room 同步
        WidgetRefreshWorker.schedulePeriodic(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
