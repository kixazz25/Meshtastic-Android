package com.geeksville.mesh.convoy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles notification action buttons for download queue.
 * Actions: CANCEL_DOWNLOAD (single), CANCEL_ALL_DOWNLOADS

 */
class ConvoyDownloadActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL = "com.grouptrack.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.grouptrack.CANCEL_ALL_DOWNLOADS"
        const val EXTRA_ENTRY_ID = "entry_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        DownloadQueueManager.init(context)
        when (intent.action) {
            ACTION_CANCEL -> {
                val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
                DownloadQueueManager.cancel(entryId)
                android.util.Log.i("DownloadAction", "Cancel from notification: $entryId")
            }
            ACTION_CANCEL_ALL -> {
                val queue = DownloadQueueManager.queue.value
                val cancellable = setOf(QueueStatus.DOWNLOADING, QueueStatus.QUEUED)
                queue.filter { it.status in cancellable }.forEach {
                    DownloadQueueManager.cancel(it.id)
                }
                android.util.Log.i("DownloadAction", "Cancel ALL from notification")
            }
        }
    }
}
