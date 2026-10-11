package com.driezy.medlog.feature.medications

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.R
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.MedicationStatusKind
import com.driezy.medlog.feature.medications.list.MyMedicationsContent
import com.driezy.medlog.feature.medications.list.MyMedicationsState
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 我的药品批量停用 / 恢复的 Compose 契约测试（docs/medication-status-spec.md §10 / §14 T7）。 */
@RunWith(AndroidJUnit4::class)
class MyMedicationsBatchContractTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun selectingMedicationsAndConfirmingDispatchesOneBatchPauseWithDate() {
        val today = LocalDate.of(2026, 9, 19)
        val dispatched = mutableListOf<Triple<Set<Long>, String, LocalDate>>()
        val a = Medication(id = 1, name = "Medicine A", doseUnit = "tablet")
        val b = Medication(id = 2, name = "Medicine B", doseUnit = "tablet")
        compose.setContent {
            MedLogTheme(dynamicColor = false) {
                MyMedicationsContent(
                    state = MyMedicationsState(listOf(a, b), loading = false),
                    onAdd = {},
                    onOpen = {},
                    onCatalog = null,
                    onSettings = {},
                    onRetry = {},
                    onSortOrderChange = {},
                    onBatchStatusChange = { ids, kind, date, _ -> dispatched += Triple(ids, kind, date) },
                    today = today,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.medication_batch)).performClick()
        compose.onNodeWithText(a.name).performClick()
        compose.onNodeWithText(b.name).performClick()
        compose.onNodeWithText(context.getString(R.string.medication_batch_pause)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.medication_batch_confirm)).performClick()

        compose.runOnIdle {
            assertEquals(1, dispatched.size)
            assertEquals(setOf(1L, 2L), dispatched.single().first)
            assertEquals(MedicationStatusKind.PAUSE, dispatched.single().second)
            assertEquals(today, dispatched.single().third)
        }
    }
}
