package dev.leandro.quicklydrop.core.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.leandro.quicklydrop.core.p2p.AndroidP2pTransport
import dev.leandro.quicklydrop.core.session.ReceiveSession
import dev.leandro.quicklydrop.core.storage.AndroidMediaStoreGateway
import dev.leandro.quicklydrop.core.storage.MediaStoreFileSink
import dev.leandro.quicklydrop.core.transfer.TransferFailure
import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import java.util.concurrent.Executors

/**
 * Keeps the receiver armed while the app is in the background.
 *
 * The service starts a [ReceiveSession], publishes the NFC payload and reports progress
 * through [TransferStatus] and a foreground notification. The session uses Wi-Fi Direct
 * and MediaStore; nothing is persisted besides the received file.
 */
class ReceiveService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    @Volatile
    private var session: ReceiveSession? = null

    @Volatile
    private var stopping = false

    private var starting = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_ARM -> arm()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        starting = false
        session?.cancel()
        session = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun arm() {
        if (starting || session != null) return

        starting = true
        stopping = false
        lastNotificationAt = 0L

        val displayName = Build.MODEL.takeIf { it.isNotBlank() }?.take(64) ?: "QuicklyDrop"
        try {
            startForeground(
                TransferNotifications.NOTIFICATION_ID,
                TransferNotifications.starting(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            finish(TransferResult.Failure(TransferFailure.INTERNAL, e.message ?: "cannot start service", e))
            return
        }

        TransferStatus.update(TransferStatusState.Preparing)
        executor.execute { runSession(displayName) }
    }

    private fun runSession(displayName: String) {
        if (stopping) return

        var activeSession: ReceiveSession? = null
        try {
            val p2p = AndroidP2pTransport(this)
            if (stopping) {
                p2p.close()
                return
            }

            val sink = MediaStoreFileSink(AndroidMediaStoreGateway(contentResolver))
            val listener = object : ReceiveSession.Listener {
                private fun current(): Boolean = session === activeSession

                override fun onArmed(payload: dev.leandro.quicklydrop.protocol.DiscoveryPayload) {
                    if (current()) notifyReady(displayName)
                }

                override fun onProgress(progress: TransferProgress) {
                    if (!current()) return
                    TransferStatus.update(TransferStatusState.Active(progress))
                    notifyProgress(progress)
                }

                override fun onCompleted(result: TransferResult) {
                    if (current()) finish(result)
                }
            }

            val active = ReceiveSession(
                p2p = p2p,
                sink = sink,
                displayName = displayName,
                listener = listener,
            )
            activeSession = active
            session = active
            if (stopping) {
                active.cancel()
                return
            }
            active.run()
        } catch (e: Exception) {
            if (session === activeSession) {
                finish(
                    TransferResult.Failure(
                        TransferFailure.INTERNAL,
                        e.message ?: "cannot receive",
                        e,
                    ),
                )
            }
        } finally {
            if (activeSession != null && session === activeSession) {
                session = null
            }
        }
    }

    private fun notifyReady(displayName: String) {
        TransferStatus.update(TransferStatusState.Armed(displayName))
        runCatching {
            notifications.notify(
                TransferNotifications.NOTIFICATION_ID,
                TransferNotifications.ready(this, displayName),
            )
        }
    }

    private fun notifyProgress(progress: TransferProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < 250) return
        lastNotificationAt = now
        runCatching {
            notifications.notify(
                TransferNotifications.NOTIFICATION_ID,
                TransferNotifications.active(this, progress),
            )
        }
    }

    private fun finish(result: TransferResult) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishOnMain(result)
        } else {
            mainHandler.post { finishOnMain(result) }
        }
    }

    private fun finishOnMain(result: TransferResult) {
        starting = false
        session = null
        lastNotificationAt = 0L
        if (stopping) {
            stopSelf()
            return
        }
        TransferStatus.update(TransferStatusState.Finished(result))
        runCatching {
            notifications.notify(
                TransferNotifications.NOTIFICATION_ID,
                TransferNotifications.finished(this, result),
            )
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopSession() {
        stopping = true
        starting = false
        session?.cancel()
        session = null
        lastNotificationAt = 0L
        TransferStatus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_ARM = "dev.leandro.quicklydrop.action.ARM"
        const val ACTION_STOP = "dev.leandro.quicklydrop.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiveService::class.java).setAction(ACTION_ARM)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReceiveService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
