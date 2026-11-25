package dev.leandro.quicklydrop.lab.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Minimal foreground service to measure whether Android allows promoting it from the
 * NFC tap. The `connectedDevice` type is the one the product will use.
 */
class SpikeForegroundService : Service() {

    companion object {
        private const val CHANNEL = "spike"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        SpikeLog.event(this, "fgs.onCreate", SpikePresence.info(this))
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "QuicklyDrop spike", NotificationManager.IMPORTANCE_LOW),
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra("source") ?: "?"
        SpikeLog.event(this, "fgs.onStart", "source=$source ${SpikePresence.info(this)}")

        return try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            SpikePresence.fgsRunning = true
            SpikeLog.event(this, "fgs.started", "source=$source")
            START_NOT_STICKY
        } catch (e: Exception) {
            SpikeLog.event(
                this,
                "fgs.failed",
                "${SpikeFailure.foregroundService(e.javaClass.name)}: ${e.message}",
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        SpikePresence.fgsRunning = false
        SpikeLog.event(this, "fgs.onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("QuicklyDrop spike")
            .setContentText("receiver active")
            .setOngoing(true)
            .build()
}
