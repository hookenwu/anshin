package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.MedicationLogDao
import com.driezy.medlog.data.model.MedicationLog
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服药日志仓库。日志通过 medicationId 继承成员，因此只有"按时间范围"的读取需要按当前成员过滤。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LogRepositoryImpl @Inject constructor(
    private val logDao: MedicationLogDao,
    private val activeRecipient: ActiveRecipientStore,
) : LogRepository {

    private fun <T> scoped(block: (Long) -> Flow<T>): Flow<T> =
        activeRecipient.recipientId.flatMapLatest { recipientId ->
            if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyFlow() else block(recipientId)
        }

    override fun getLogsForDateRange(startMs: Long, endMs: Long): Flow<List<MedicationLog>> =
        scoped { logDao.getLogsForDateRange(it, startMs, endMs) }

    override fun getLogsForMedication(medicationId: Long, limit: Int): Flow<List<MedicationLog>> =
        logDao.getLogsForMedication(medicationId, limit)

    override suspend fun getLogForMedicationAndDate(medicationId: Long, startMs: Long, endMs: Long): MedicationLog? =
        logDao.getLogForMedicationAndDate(medicationId, startMs, endMs)

    override suspend fun getLogForScheduledTime(medicationId: Long, scheduledTimeMs: Long): MedicationLog? =
        logDao.getLogForScheduledTime(medicationId, scheduledTimeMs)

    override suspend fun insertLog(log: MedicationLog): Long = logDao.insertLog(log)

    override suspend fun updateLog(log: MedicationLog) = logDao.updateLog(log)

    override suspend fun deleteLog(log: MedicationLog) = logDao.deleteLog(log)

    override suspend fun deleteLogsForDate(medicationId: Long, startMs: Long, endMs: Long) =
        logDao.deleteLogsForMedicationAndDate(medicationId, startMs, endMs)

    override suspend fun deleteLogForScheduledTime(medicationId: Long, scheduledTimeMs: Long) =
        logDao.deleteLogForScheduledTime(medicationId, scheduledTimeMs)

    override fun getTakenCountForDateRange(startMs: Long, endMs: Long): Flow<Int> =
        scoped { logDao.getTakenCountForDateRange(it, startMs, endMs) }

    override suspend fun getLogsForRangeOnce(startMs: Long, endMs: Long): List<MedicationLog> {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) return emptyList()
        return logDao.getLogsForRangeOnce(recipientId, startMs, endMs)
    }
}
