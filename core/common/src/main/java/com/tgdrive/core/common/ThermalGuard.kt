package com.tgdrive.core.common

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Hardware thermal state helpers to throttle or pause background CPU/disk work before
 * the SoC hits sustained thermal throttling.
 */
object ThermalGuard {
    fun isThermalThrottling(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        }.getOrDefault(false)
    }

    fun isThermalSevere(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }.getOrDefault(false)
    }
}
