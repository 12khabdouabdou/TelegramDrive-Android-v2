package com.tgdrive.feature.autobackup.watcher

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import com.tgdrive.core.common.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderWatcher @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private var thread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private var lastTriggerMs: Long = 0
    private val debounceMs: Long = 15L * 60 * 1000  // 15 minutes

    private val triggers = mutableListOf<() -> Unit>()

    /** Add a callback to invoke when a relevant change is observed. */
    fun onChange(block: () -> Unit) { triggers += block }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("FolderWatcher").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerMs < debounceMs) return
                lastTriggerMs = now
                Log.i("FolderWatcher: change observed at $uri; firing triggers")
                triggers.forEach { runCatching(it).onFailure { e -> Log.w("trigger failed", e) } }
            }
        }
        observer = obs

        ctx.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
        )
        ctx.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs
        )
        ctx.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs
        )
        Log.i("FolderWatcher started")
    }

    fun stop() {
        observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
        observer = null
        thread?.quitSafely()
        thread = null
        Log.i("FolderWatcher stopped")
    }
}
