package com.tgdrive.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tgdrive.core.common.Log
import com.tgdrive.feature.autobackup.BackupScheduler
import com.tgdrive.feature.autobackup.ScheduledUploadScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-schedules the periodic AutoBackup work after device reboot or app update.
 *
 * WorkManager actually persists PeriodicWorkRequest across reboots itself,
 * but we re-enqueue explicitly to be safe (e.g. if the user disabled battery
 * optimisations and the OS killed the worker queue).
 *
 * **Bug history:** Previously this receiver launched a coroutine without
 * calling `goAsync()`, so the system could kill the process before the
 * suspend `rescheduleIfEnabled()` call completed. Also, an uncaught
 * exception would crash the app from a broadcast receiver context.
 * Now we use `goAsync()` + try/catch + `pendingResult.finish()`.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var backupScheduler: BackupScheduler
    @Inject lateinit var scheduledUploadScheduler: ScheduledUploadScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // goAsync() lets the system keep the receiver alive while we do
        // async work. Without it, the system may kill the process before
        // the suspend call completes.
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                backupScheduler.rescheduleIfEnabled()
                scheduledUploadScheduler.rescheduleIfEnabled()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BootCompletedReceiver: reschedule failed", t)
            } finally {
                try { pendingResult.finish() } catch (_: Throwable) {}
                scope.cancel()
            }
        }
    }
}
