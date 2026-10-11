package com.driezy.medlog.data.repository

import com.driezy.medlog.data.local.MedicationStatusEventDao
import com.driezy.medlog.data.model.MedicationStatusEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** `MedicationStatusRepository` 的 Room 实现：纯 append，映射到 [MedicationStatusEventDao]。 */
@Singleton
class MedicationStatusRepositoryImpl @Inject constructor(private val dao: MedicationStatusEventDao) :
    MedicationStatusRepository {

    override fun observeEvents(medicationId: Long): Flow<List<MedicationStatusEvent>> =
        dao.getEventsForMedication(medicationId)

    override suspend fun getEventsOnce(medicationId: Long): List<MedicationStatusEvent> =
        dao.getEventsForMedicationOnce(medicationId)

    override suspend fun getEventById(id: Long): MedicationStatusEvent? = dao.getEventById(id)

    override fun observeAllEvents(): Flow<List<MedicationStatusEvent>> = dao.getAllEvents()

    override suspend fun getEventsForRecipientOnce(recipientId: Long): List<MedicationStatusEvent> =
        dao.getEventsForRecipientOnce(recipientId)

    override suspend fun insertEvent(event: MedicationStatusEvent): Long = dao.insertEvent(event)
}
