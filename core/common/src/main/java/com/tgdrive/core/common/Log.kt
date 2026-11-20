package com.tgdrive.core.common

import android.util.Log as AndroidLog

/**
 * Tiny wrapper around [android.util.Log] so we can flip the tag centrally
 * and disable verbose logging in release builds.
 *
 * In release builds, set `DEBUG = false` and `d(...)` becomes a no-op.
 */
object Log {
    private const val TAG = "TGDrive"

    /** Initialized by Application from BuildConfig.DEBUG to suppress d/i logs in release builds. */
    @Volatile var isDebug: Boolean = false

    fun d(msg: String) { if (isDebug) AndroidLog.d(TAG, msg) }
    fun i(msg: String) { if (isDebug) AndroidLog.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) = AndroidLog.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = AndroidLog.e(TAG, msg, t)
}
