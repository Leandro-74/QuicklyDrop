package dev.leandro.quicklydrop.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.leandro.quicklydrop.core.R
import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult

/** Builds the foreground and completion notifications. */
object TransferNotifications {

    const val CHANNEL_ID = "transfers"
    const val NOTIFICATION_ID = 1001

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun starting(context: Context): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.notification_starting_title))
            .setContentText(context.getString(R.string.notification_starting_text))
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notification_cancel), stopIntent(context))
            .build()

    fun ready(context: Context, displayName: String): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.notification_ready_title))
            .setContentText(context.getString(R.string.notification_ready_text, displayName))
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notification_cancel), stopIntent(context))
            .build()

    fun active(context: Context, progress: TransferProgress): Notification {
        val percent = (progress.totalFraction * 100).toInt().coerceIn(0, 100)
        return base(context)
            .setContentTitle(
                context.getString(R.string.notification_receiving_title, progress.file.name),
            )
            .setContentText(
                context.getString(
                    R.string.notification_receiving_text,
                    TransferFormat.bytes(progress.fileBytes),
                    TransferFormat.bytes(progress.file.size),
                ),
            )
            .setProgress(100, percent, false)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notification_cancel), stopIntent(context))
            .build()
    }

    fun finished(context: Context, result: TransferResult): Notification {
        val success = result is TransferResult.Success
        val title = if (success) {
            context.getString(R.string.notification_complete_title)
        } else {
            context.getString(R.string.notification_failed_title)
        }
        val text = when (result) {
            is TransferResult.Success -> context.getString(
                R.string.notification_complete_text,
                result.files,
                TransferFormat.bytes(result.bytes),
            )

            is TransferResult.Failure -> result.message
        }

        return base(context)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    private fun base(context: Context): Notification.Builder {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
        contentIntent(context)?.let(builder::setContentIntent)
        return builder
    }

    private fun contentIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(context, 1, launch, FLAGS)
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReceiveService::class.java)
            .setAction(ReceiveService.ACTION_STOP)
        return PendingIntent.getService(context, 2, intent, FLAGS)
    }
}
