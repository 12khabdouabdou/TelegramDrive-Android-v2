package com.tgdrive.core.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tgdrive.core.common.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles notification action buttons on [TransferKeepAliveWorker] so active
 * uploads can be paused, resumed, or stopped directly from the Android shade.
 */
@AndroidEntryPoint
class TransferActionReceiver : BroadcastReceiver() {

    @Inject lateinit var uploadManager: UploadManager
    @Inject lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_ALL -> {
                Log.i("TransferActionReceiver: pausing active transfers")
                uploadManager.pauseAll()
                downloadManager.pauseAll()
            }
            ACTION_CANCEL_ALL -> {
                Log.i("TransferActionReceiver: cancelling active transfers")
                uploadManager.cancelAll()
                downloadManager.state.value.items
                    .filter {
                        it.status == DownloadStatus.QUEUED ||
                            it.status == DownloadStatus.DOWNLOADING ||
                            it.status == DownloadStatus.PAUSED
                    }
                    .forEach { downloadManager.cancel(it.chatId, it.id) }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.tgdrive.transfer.action.PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.tgdrive.transfer.action.CANCEL_ALL"
    }
}
