package com.tgdrive.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tgdrive.app.boot.NotificationChannels
import com.tgdrive.app.coil.EncryptedThumbFetcher
import com.tgdrive.app.coil.EncryptedThumbKeyer
import com.tgdrive.core.common.CrashLogStore
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.feature.autobackup.BackupScheduler
import com.tgdrive.feature.autobackup.ScheduledUploadScheduler
import com.tgdrive.feature.autobackup.watcher.FolderWatcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltAndroidApp
class TelegramDriveApp :
    Application(),
    Configuration.Provider,
    ImageLoaderFactory {

    @Inject lateinit var workerFactory: dagger.Lazy<HiltWorkerFactory>
    @Inject lateinit var keystoreHelper: dagger.Lazy<KeystoreHelper>
    @Inject lateinit var telegramClient: dagger.Lazy<TelegramClient>
    @Inject lateinit var preferences: dagger.Lazy<AppPreferences>
    @Inject lateinit var folderWatcher: dagger.Lazy<FolderWatcher>
    @Inject lateinit var backupScheduler: dagger.Lazy<BackupScheduler>
    @Inject lateinit var scheduledUploadScheduler: dagger.Lazy<ScheduledUploadScheduler>
    @Inject lateinit var syncLogDao: dagger.Lazy<SyncLogDao>
    @Inject lateinit var mediaThumbnailCache: dagger.Lazy<MediaThumbnailCache>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.isDebug = BuildConfig.DEBUG
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashLogStore.save(this, thread, throwable)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
        NotificationChannels.ensureCreated(this)

        // NOTE: Do NOT install a global Thread.setDefaultUncaughtExceptionHandler.
        // A previous version (v0.12.0) installed one that logged exceptions
        // and continued. This caused the ENTIRE APP TO FREEZE because:
        //
        //   1. A background thread throws an exception.
        //   2. The handler logs it but doesn't terminate the thread.
        //   3. The thread continues, hits the same broken code, throws again.
        //   4. The handler logs again. Repeat forever in a tight loop.
        //   5. If the exception happens on the MAIN thread (e.g., a Compose
        //      recomposition touching broken state), the main thread is
        //      consumed by exception-logging → entire app freezes.
        //
        // The correct approach is per-component crash guards:
        //   - BackupWorker.doWork() has a catch(Throwable) that converts
        //     crashes to Result.failure.
        //   - Each ViewModel catches exceptions in its coroutines.
        //   - TDLib native exceptions are handled by TDLib's own callback.
        //
        // If the app crashes, it's better to crash cleanly (and let Android
        // restart it) than to freeze forever.

        // Run all init tasks in PARALLEL so a slow/hanging TDLib init doesn't
        // block stale-row recovery or FolderWatcher start.

        // Task 1: Auto-initialize TDLib with stored creds (if any).
        // Time-bounded to 30s so a hanging init doesn't block the app forever.
        // If it times out, the user just sees the auth screen (RootViewModel's
        // splash timer handles this).
        appScope.launch {
            try {
                val creds = keystoreHelper.get().getApiCreds()
                val parsedId = creds?.apiId?.toIntOrNull()
                if (creds == null || parsedId == null || parsedId <= 0) {
                    Log.i("TelegramDriveApp: no stored API creds — skipping TDLib init (fresh install)")
                    return@launch
                }
                Log.i("TelegramDriveApp: auto-initializing TDLib with stored creds")
                val ok = withTimeoutOrNull(TDLIB_INIT_TIMEOUT_MS) {
                    telegramClient.get().initialize(
                        apiId = parsedId,
                        apiHash = creds.apiHash,
                        sessionDir = "tgdrive_tdlib",
                    )
                }
                if (ok == null) {
                    Log.w("TelegramDriveApp: TDLib init timed out after ${TDLIB_INIT_TIMEOUT_MS}ms — " +
                          "user will see auth screen; TDLib may finish initializing in the background")
                } else {
                    launch {
                        runCatching {
                            val limit = preferences.get().data.first().cacheLimitBytes
                            telegramClient.get().optimizeStorage(limit)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TelegramDriveApp: auto-init failed", t)
            }
        }

        // Task 2: Recover from a previous-process crash — mark stale RUNNING
        // sync_log rows as CANCELLED. Independent of TDLib.
        appScope.launch {
            try {
                val n = syncLogDao.get().markRunningAsCancelled(System.currentTimeMillis())
                if (n > 0) Log.i("TelegramDriveApp: recovered $n stale RUNNING sync_log row(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TelegramDriveApp: stale RUNNING recovery failed", t)
            }
        }

        // Task 3: Register FolderWatcher callback + reschedule periodic & on-change work off main thread.
        appScope.launch {
            try {
                registerFolderWatcherCallback()
                backupScheduler.get().rescheduleIfEnabled()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TelegramDriveApp: rescheduleIfEnabled failed", t)
            }
            try {
                scheduledUploadScheduler.get().rescheduleIfEnabled()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TelegramDriveApp: scheduledUploadScheduler failed", t)
            }
            com.tgdrive.core.download.TrashPurgeWorker.scheduleDaily(this@TelegramDriveApp)
            com.tgdrive.core.download.ai.MediaAiIndexWorker.schedulePeriodic(this@TelegramDriveApp)
        }
    }

    /** Wire up FolderWatcher's onChange callback to enqueue an on-change backup. */
    private fun registerFolderWatcherCallback() {
        folderWatcher.get().onChange {
            appScope.launch {
                try {
                    val prefs = preferences.get().data.first()
                    if (prefs.backupEnabled && !prefs.backupPaused &&
                        prefs.backupSchedule == BackupSchedule.ON_CHANGE
                    ) {
                        Log.i("TelegramDriveApp: ON_CHANGE trigger → enqueuing backup")
                        backupScheduler.get().enqueueOnChange()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("TelegramDriveApp: ON_CHANGE trigger failed", t)
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(EncryptedThumbKeyer())
                add(EncryptedThumbFetcher.Factory { mediaThumbnailCache.get() })
                add(VideoFrameDecoder.Factory())
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    @Suppress("DEPRECATION")
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    companion object {
        /** Maximum time to wait for TDLib initialization. If it takes longer,
         *  we give up and let the user see the auth screen. TDLib may finish
         *  initializing in the background — the auth screen will pick up the
         *  state change via [telegramClient.authState] flow. */
        private const val TDLIB_INIT_TIMEOUT_MS = 30_000L
    }
}
