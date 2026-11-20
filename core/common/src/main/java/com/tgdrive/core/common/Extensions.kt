package com.tgdrive.core.common

import java.text.DecimalFormat

/** Format byte counts as human-readable sizes (B / KB / MB / GB / TB). */
fun Long.toHumanSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(toDouble()) / Math.log10(1024.0)).toInt()
        .coerceAtMost(units.lastIndex)
    val pattern = if (digitGroups == 0) "#0" else "#0.#"
    return "${DecimalFormat(pattern).format(this / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}

/** Format an epoch-millis to ISO-8601 (UTC). */
fun Long.toIsoUtc(): String =
    java.time.Instant.ofEpochMilli(this).toString()

/** Format an epoch-millis to a short local date (yyyy-MM-dd HH:mm). */
fun Long.toLocalShort(): String {
    val z = java.time.Instant.ofEpochMilli(this)
        .atZone(java.time.ZoneId.systemDefault())
    return "${z.toLocalDate()} ${z.toLocalTime().toString().substring(0, 5)}"
}

/** Truncate a string to [n] chars with an ellipsis. */
fun String.ellipsize(n: Int): String =
    if (length <= n) this else substring(0, (n - 1).coerceAtLeast(1)) + "…"

/** True if [this] looks like an E.164 phone number. */
fun String.isE164(): Boolean = "^\\+?[1-9]\\d{6,14}$".toRegex().matches(this)

/** Run [block] and swallow any exception, returning null on failure. */
inline fun <T> tryNull(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
