package com.driezy.medlog.feature.caretasks.application

import com.driezy.medlog.data.model.HealthRecord
import com.driezy.medlog.data.model.HealthRecordSource
import com.driezy.medlog.data.model.HealthType
import com.driezy.medlog.data.repository.HealthRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * T8：过程数据写入健康表 + 防重。
 *
 * 覆盖 key 的三段构成、首次写入各字段（含 MANUAL、Clock 时间戳、备注透传），
 * 以及「同一时间槽同一指标」判重不写第二行。
 */
class RecordCareTaskMeasurementUseCaseTest {

    private val nowMs = 1_763_942_400_000L
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
    private val health: HealthRepository = mock()
    private val useCase = RecordCareTaskMeasurementUseCase(health, clock)

    private val taskId = 7L
    private val slot = nowMs

    @Test
    fun `sourceCacheKey is derived from task, slot and metric`() {
        val key = RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId, slot, HealthType.SPO2)

        assertEquals("caretask:7:$slot:SPO2", key)
        assertTrue(
            "不同时间槽必须得到不同的 key",
            key != RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId, slot + 1, HealthType.SPO2),
        )
        assertTrue(
            "同一时间槽的不同指标必须得到不同的 key",
            key != RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId, slot, HealthType.OXYGEN_FLOW),
        )
        assertTrue(
            "不同事项必须得到不同的 key",
            key != RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId + 1, slot, HealthType.SPO2),
        )
    }

    @Test
    fun `first recording writes a manual record with the clock timestamp`() = runTest {
        val key = RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId, slot, HealthType.SPO2)
        whenever(health.hasSourceCacheKey(key)).thenReturn(false)
        whenever(health.addRecord(any())).thenReturn(42L)

        val result = useCase.record(
            taskId = taskId,
            scheduledTimeMs = slot,
            type = HealthType.SPO2,
            value = 97.0,
            notes = "指夹血氧",
        )

        assertEquals(CareTaskMeasurementResult.Recorded(42L), result)
        val saved = captureRecord()
        assertEquals("SPO2", saved.type)
        assertEquals(97.0, saved.value, 0.0)
        assertNull(saved.secondaryValue)
        assertEquals("指夹血氧", saved.notes)
        assertEquals(HealthRecordSource.MANUAL, saved.source)
        assertEquals(key, saved.sourceCacheKey)
        assertEquals("时间戳取自注入的 Clock", nowMs, saved.timestamp)
        assertEquals(nowMs, saved.confirmedAt)
    }

    @Test
    fun `a secondary value is passed through when provided`() = runTest {
        whenever(health.hasSourceCacheKey(any())).thenReturn(false)
        whenever(health.addRecord(any())).thenReturn(1L)

        useCase.record(taskId, slot, HealthType.READING_COUNT, value = 20.0, secondaryValue = 3.0)

        val saved = captureRecord()
        assertEquals("READING_COUNT", saved.type)
        assertEquals(20.0, saved.value, 0.0)
        assertEquals(3.0, saved.secondaryValue!!, 0.0)
    }

    @Test
    fun `recording the same metric twice is suppressed and reported without writing`() = runTest {
        val key = RecordCareTaskMeasurementUseCase.sourceCacheKey(taskId, slot, HealthType.OXYGEN_FLOW)
        whenever(health.hasSourceCacheKey(key)).thenReturn(true)

        val result = useCase.record(taskId, slot, HealthType.OXYGEN_FLOW, value = 2.0)

        assertEquals(CareTaskMeasurementResult.Duplicate(key), result)
        verify(health, never()).addRecord(any())
    }

    @Test
    fun `a different metric on the same slot is not a duplicate`() = runTest {
        whenever(health.hasSourceCacheKey(any())).thenReturn(false)
        whenever(health.addRecord(any())).thenReturn(9L)

        val result = useCase.record(taskId, slot, HealthType.BLOOD_PRESSURE, value = 118.0)

        assertEquals(CareTaskMeasurementResult.Recorded(9L), result)
        verify(health).addRecord(any())
    }

    private suspend fun captureRecord(): HealthRecord {
        val captor = argumentCaptor<HealthRecord>()
        verify(health).addRecord(captor.capture())
        return captor.firstValue
    }
}
