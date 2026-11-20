package com.tgdrive.core.common.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import com.tgdrive.core.common.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide controller for Picture-in-Picture mode.
 *
 * Bridges the Compose layer (VideoPlayerScreen) and the Activity layer
 * (MainActivity.onUserLeaveHint). The screen calls [setActive] when it
 * wants PiP; the Activity checks [shouldEnterPip] in onUserLeaveHint
 * and calls [enterPip] if true.
 *
 * Why a singleton instead of just an Activity flag: the VideoPlayerScreen
 * doesn't have a direct reference to the Activity (Compose best practice).
 * This controller is Hilt-injected, so the screen can signal without
 * coupling, and the Activity can observe without polling.
 *
 * Requires API 26+ (PiP was added in Oreo). On older devices, all calls
 * are no-ops.
 */
@Singleton
class PipController @Inject constructor() {

    @Volatile
    private var active: Boolean = false

    @Volatile
    private var aspectRatio: Rational? = null

    /**
     * Tell the controller that the current screen wants PiP when the
     * user leaves (e.g. presses Home while a video is playing).
     *
     * @param active true to enable PiP-on-leave, false to disable
     * @param aspectRatioWidth the video width for PiP aspect ratio (16:9 default)
     * @param aspectRatioHeight the video height for PiP aspect ratio
     */
    fun setActive(active: Boolean, aspectRatioWidth: Int = 16, aspectRatioHeight: Int = 9) {
        this.active = active
        this.aspectRatio = if (active) Rational(aspectRatioWidth, aspectRatioHeight) else null
    }

    /** True if the current screen wants PiP when the user leaves. */
    fun shouldEnterPip(): Boolean = active

    /** True if PiP is supported on this device. */
    fun isPipSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Enter PiP mode on the given [activity]. Called from
     * [android.app.Activity.onUserLeaveHint] when [shouldEnterPip] is true.
     *
     * No-op on API < 26 or if PiP is not supported.
     */
    fun enterPip(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!activity.packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            Log.i("PipController: PiP not supported on this device")
            return
        }
        try {
            val builder = PictureInPictureParams.Builder()
            aspectRatio?.let { ratio ->
                builder.setAspectRatio(ratio)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            activity.enterPictureInPictureMode(builder.build())
            Log.i("PipController: entered PiP")
        } catch (t: Throwable) {
            Log.w("PipController: enterPip failed", t)
        }
    }

    /** True if the activity is currently in PiP mode. */
    fun isInPip(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return activity.isInPictureInPictureMode
    }
}
