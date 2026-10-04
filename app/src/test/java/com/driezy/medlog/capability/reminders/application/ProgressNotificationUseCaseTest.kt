package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.capability.reminders.NotificationHelper
import com.driezy.medlog.data.model.CareRecipient
import com.driezy.medlog.data.repository.CareRecipientRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProgressNotificationUseCaseTest {

    private val notificationHelper: NotificationHelper = mock()
    private val careRecipients: CareRecipientRepository = mock()
    private val useCase = ProgressNotificationUseCase(notificationHelper, careRecipients)

    @Test
    fun `invoke updates today progress notification`() = runTest {
        whenever(careRecipients.activeRecipient()).thenReturn(
            CareRecipient(id = 1L, uuid = "uuid-1", displayName = "妈妈"),
        )

        useCase(taken = 2, total = 4, pendingNames = listOf("Amoxicillin"))

        verify(notificationHelper).showOrUpdateProgressNotification(
            taken = 2,
            total = 4,
            pendingNames = listOf("Amoxicillin"),
            memberName = "妈妈",
        )
    }

    @Test
    fun `dismiss removes today progress notification`() {
        useCase.dismiss()

        verify(notificationHelper).dismissProgressNotification()
    }
}
