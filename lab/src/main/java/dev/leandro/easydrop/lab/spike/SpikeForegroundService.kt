package dev.leandro.easydrop.lab.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Serviço em primeiro plano mínimo para medir se o Android permite promovê-lo a partir
 * do toque NFC. O tipo `connectedDevice` é o que o produto vai usar.
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
                NotificationChannel(CHANNEL, "EasyDrop spike", NotificationManager.IMPORTANCE_LOW),
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
            .setContentTitle("EasyDrop spike")
            .setContentText("receptor ativo")
            .setOngoing(true)
            .build()
}
