package com.driezy.medlog.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.model.InvalidStatusEvent
import com.driezy.medlog.data.model.InvalidSupersedeReference
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusEvent
import com.driezy.medlog.data.model.MedicationStatusKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 状态事件 DAO 的 supersedesId 不变量（docs/medication-status-spec.md §4 / §5 R6 / §9.1.6/9.1.7）。
 *
 * 写入端拒绝**跨药**与**悬挂**引用（结构性杜绝环，并保证跨药隔离由 DAO 强制，而非仅靠调用方分组）。
 * 这与派生端 `MedicationStatusDerivation.liveEvents` 对悬挂/跨药引用的安全退化（不抑制）互为两道闸。
 */
@RunWith(AndroidJUnit4::class)
class MedicationStatusEventIsolationTest {

    private fun inMemoryDatabase(): MedLogDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        MedLogDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun crossMedicationAndDanglingSupersedesAreRejectedAndNeverSuppressAnotherMedsEvent() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val recipient = database.careRecipientDao().insert(
                CareRecipient(uuid = CareRecipient.newUuid(), displayName = "爸爸"),
            )
            val medA = database.medicationDao().insertMedication(
                Medication(careRecipientId = recipient, name = "A", doseUnit = "片"),
            )
            val medB = database.medicationDao().insertMedication(
                Medication(careRecipientId = recipient, name = "B", doseUnit = "片"),
            )
            val dao = database.medicationStatusEventDao()

            val aEvent = dao.insertEvent(
                MedicationStatusEvent(
                    medicationId = medA,
                    kind = MedicationStatusKind.PAUSE,
                    effectiveAtMs = 1_000L,
                    recordedAtMs = 1L,
                ),
            )

            // 跨药：B 的事件指向 A 的事件 → 拒绝
            val cross = runCatching {
                dao.insertEvent(
                    MedicationStatusEvent(
                        medicationId = medB,
                        kind = MedicationStatusKind.RESUME,
                        effectiveAtMs = 2_000L,
                        recordedAtMs = 2L,
                        supersedesId = aEvent,
                    ),
                )
            }
            assertTrue("跨药 supersedesId 必须被拒绝", cross.exceptionOrNull() is InvalidSupersedeReference)

            // 悬挂：指向不存在的 id → 拒绝
            val dangling = runCatching {
                dao.insertEvent(
                    MedicationStatusEvent(
                        medicationId = medB,
                        kind = MedicationStatusKind.RESUME,
                        effectiveAtMs = 3_000L,
                        recordedAtMs = 3L,
                        supersedesId = 99_999L,
                    ),
                )
            }
            assertTrue("悬挂 supersedesId 必须被拒绝", dangling.exceptionOrNull() is InvalidSupersedeReference)

            // A 的事件不受任何影响（跨药隔离）
            assertEquals(listOf(aEvent), dao.getEventsForMedicationOnce(medA).map { it.id })
            assertEquals(0, dao.getEventsForMedicationOnce(medB).size)

            // 同药取代正常写入（append-only，旧行保留）
            val corrected = dao.insertEvent(
                MedicationStatusEvent(
                    medicationId = medA,
                    kind = MedicationStatusKind.RESUME,
                    effectiveAtMs = 4_000L,
                    recordedAtMs = 4L,
                    supersedesId = aEvent,
                ),
            )
            assertEquals(listOf(aEvent, corrected), dao.getEventsForMedicationOnce(medA).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun effectiveAtMsNullableInvariantIsEnforced() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val recipient = database.careRecipientDao().insert(
                CareRecipient(uuid = CareRecipient.newUuid(), displayName = "妈妈"),
            )
            val med = database.medicationDao().insertMedication(
                Medication(careRecipientId = recipient, name = "C", doseUnit = "片"),
            )
            val dao = database.medicationStatusEventDao()

            // 非基线 kind 的生效日不得为 NULL
            val nullPause = runCatching {
                dao.insertEvent(
                    MedicationStatusEvent(
                        medicationId = med,
                        kind = MedicationStatusKind.PAUSE,
                        effectiveAtMs = null,
                        recordedAtMs = 1L,
                    ),
                )
            }
            assertTrue(nullPause.exceptionOrNull() is InvalidStatusEvent)

            // 基线 kind 必须为 NULL 生效日
            val datedBaseline = runCatching {
                dao.insertEvent(
                    MedicationStatusEvent(
                        medicationId = med,
                        kind = MedicationStatusKind.LEGACY_BASELINE,
                        effectiveAtMs = 1L,
                        recordedAtMs = 2L,
                    ),
                )
            }
            assertTrue(datedBaseline.exceptionOrNull() is InvalidStatusEvent)
        } finally {
            database.close()
        }
    }
}
