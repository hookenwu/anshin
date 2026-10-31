package com.driezy.medlog.feature.medications

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.driezy.medlog.R
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.feature.medications.list.MyMedicationsContent
import com.driezy.medlog.feature.medications.list.MyMedicationsState
import com.driezy.medlog.ui.theme.MedLogTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 我的药品行内的提醒偏移（B12）契约：非零出现在行里，0 时行里不出现任何偏移文字。
 * 用户报的缺口正是「详情页有、列表行没有」。
 */
@RunWith(AndroidJUnit4::class)
class MyMedicationsRowOffsetContractTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun med(id: Long, name: String, offset: Int, time: String) = Medication(
        id = id,
        name = name,
        doseUnit = "片",
        timePeriod = "exact",
        reminderTimes = time,
        offsetMinutes = offset,
    )

    @Test fun theRowShowsALaterAndAnEarlierOffsetButNothingAtZero() {
        val later = med(1, "Medicine Later", 15, "12:00")
        val earlier = med(2, "Medicine Earlier", -15, "18:00")
        val plain = med(3, "Medicine Plain", 0, "09:00")
        compose.setContent {
            MedLogTheme(dynamicColor = false) {
                MyMedicationsContent(
                    state = MyMedicationsState(listOf(later, earlier, plain), loading = false),
                    onAdd = {},
                    onOpen = {},
                    onCatalog = null,
                    onSettings = {},
                    onRetry = {},
                    onSortOrderChange = {},
                    onBatchStatusChange = { _, _, _, _ -> },
                    today = LocalDate.of(2026, 10, 31),
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.medication_row_offset_later, 15), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.medication_row_offset_earlier, 15), substring = true)
            .assertIsDisplayed()
        // 0 偏移那行：整行文本必须与「不含偏移」的逐字一致——多一个标点都会失配
        val plainRow = "1 片 · " + context.getString(R.string.detail_freq_daily) + " · 09:00"
        compose.onNodeWithText(plainRow).assertIsDisplayed()
    }
}
