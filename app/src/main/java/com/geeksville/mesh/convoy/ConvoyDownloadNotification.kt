package com.geeksville.mesh.convoy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Notification channel and builders for background tile downloads.
 * Channel created once on DownloadQueueManager.init().
 */
object ConvoyDownloadNotification {

    const val CHANNEL_ID = "grouptrack_tile_downloads"
    private const val CHANNEL_NAME = "Map Downloads"
    private const val CHANNEL_DESC = "Offline map tile download progress"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    fun progressNotification(
        context: Context, label: String, downloaded: Int, total: Int
    ): NotificationCompat.Builder {
        val pct = if (total > 0) (downloaded * 100 / total) else 0
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: $label")
            .setContentText("$downloaded / $total tiles ($pct%)")
            .setProgress(total, downloaded, false)
            .setOngoing(true)
            .setSilent(true)
    }

    fun completeNotification(
        context: Context, label: String, tileCount: Int
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText("$label -- $tileCount tiles")
            .setAutoCancel(true)
    }

    fun failedNotification(
        context: Context, label: String, message: String
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText("$label -- $message")
            .setAutoCancel(true)
    }
}
