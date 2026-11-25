package dev.leandro.quicklydrop.core.service

import android.app.Notification
import android.app.NotificationManager
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.FileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferNotificationsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TransferNotifications.createChannel(context)
    }

    @Test
    fun `creates the transfer channel`() {
        val manager = context.getSystemService(NotificationManager::class.java)

        assertNotNull(manager.getNotificationChannel(TransferNotifications.CHANNEL_ID))
    }

    @Test
    fun `starting notification is ongoing`() {
        val notification = TransferNotifications.starting(context)

        assertEquals(
            "Starting receiver",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `ready notification is ongoing and cancellable`() {
        val notification = TransferNotifications.ready(context, "Pixel")

        assertEquals(
            "Ready to receive",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions?.size ?: 0)
    }

    @Test
    fun `active notification carries progress`() {
        val file = FileMeta("a.bin", 100, "application/octet-stream")
        val progress = TransferProgress(
            fileIndex = 0,
            filesTotal = 1,
            file = file,
            fileBytes = 50,
            totalBytes = 100,
            transferredBytes = 50,
        )

        val notification = TransferNotifications.active(context, progress)

        assertEquals(50, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `success notification is dismissible`() {
        val notification = TransferNotifications.finished(
            context,
            TransferResult.Success(files = 2, bytes = 2048),
        )

        assertEquals(
            "Transfer complete",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `failure notification shows the failure message`() {
        val notification = TransferNotifications.finished(
            context,
            TransferResult.Failure(TransferFailure.NO_SPACE, "disk full"),
        )

        assertEquals(
            "Transfer failed",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals("disk full", notification.extras.getString(Notification.EXTRA_TEXT))
    }
}
