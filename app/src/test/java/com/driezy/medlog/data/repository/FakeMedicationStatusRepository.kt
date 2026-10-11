package com.driezy.medlog.data.repository

import com.driezy.medlog.data.model.InvalidSupersedeReference
import com.driezy.medlog.data.model.MedicationStatusEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * append-only 状态事件的纯内存假实现（JVM 单测用）。与 DAO 同语义：只 append，
 * supersedesId 必须指向同药既有行。
 */
class FakeMedicationStatusRepository : MedicationStatusRepository {

    private val eventsState = MutableStateFlow<List<MedicationStatusEvent>>(emptyList())
    private var nextId = 1L

    val events: List<MedicationStatusEvent> get() = eventsState.value

    override fun observeEvents(medicationId: Long): Flow<List<MedicationStatusEvent>> =
        eventsState.map { list -> list.filter { it.medicationId == medicationId }.sortedBy { it.id } }

    override suspend fun getEventsOnce(medicationId: Long): List<MedicationStatusEvent> =
        eventsState.value.filter { it.medicationId == medicationId }.sortedBy { it.id }

    override suspend fun getEventById(id: Long): MedicationStatusEvent? = eventsState.value.find { it.id == id }

    override fun observeAllEvents(): Flow<List<MedicationStatusEvent>> = eventsState

    override suspend fun getEventsForRecipientOnce(recipientId: Long): List<MedicationStatusEvent> = eventsState.value

    override suspend fun insertEvent(event: MedicationStatusEvent): Long {
        val supersedes = event.supersedesId
        if (supersedes != null) {
            val target = eventsState.value.find { it.id == supersedes }
                ?: throw InvalidSupersedeReference("supersedesId=$supersedes 不存在")
            if (target.medicationId != event.medicationId) {
                throw InvalidSupersedeReference("supersedesId=$supersedes 跨药")
            }
        }
        val id = if (event.id != 0L) event.id else nextId++
        nextId = maxOf(nextId, id + 1)
        eventsState.value = eventsState.value + event.copy(id = id)
        return id
    }
}
