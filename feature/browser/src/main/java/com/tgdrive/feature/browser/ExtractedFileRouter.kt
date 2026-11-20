package com.tgdrive.feature.browser

/**
 * Route for a file extracted from a ZIP archive.
 *
 * - [ImageView] → open in-app via [com.tgdrive.feature.player.ImageViewer]
 *   (better UX than external gallery: zoom + pan + swipe-to-dismiss)
 * - [External] → open via [android.content.Intent.ACTION_VIEW] + FileProvider
 */
sealed interface ExtractedFileRoute {
    data class ImageView(
        val localPath: String,
    ) : ExtractedFileRoute

    data class External(
        val localPath: String,
        val mimeType: String,
    ) : ExtractedFileRoute
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "heif", "avif")

/**
 * Decide how to open a file extracted from a ZIP.
 *
 * Images go through the in-app [com.tgdrive.feature.player.ImageViewer] so
 * the user stays inside TelegramDrive (zoom/pan/swipe-to-dismiss). All
 * other types (videos, PDFs, audio, docs) go through [android.content.Intent.ACTION_VIEW]
 * with the appropriate MIME type — external apps handle these well, and
 * building a separate in-app viewer for each would duplicate the OS's job.
 *
 * Extension matching is case-insensitive.
 */
fun routeForExtractedFile(
    fileName: String,
    localPath: String,
): ExtractedFileRoute {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTS -> ExtractedFileRoute.ImageView(localPath)
        else -> ExtractedFileRoute.External(localPath, guessMimeFromName(fileName))
    }
}

/**
 * MIME-type guesser for file names. Used by [routeForExtractedFile] and
 * by the ZIP browser when launching [android.content.Intent.ACTION_VIEW].
 *
 * Kept here (not in :core:common) because the only callers are in
 * `:feature:browser`. If a second module needs it, promote it then.
 */
internal fun guessMimeFromName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "*/*"
    }
}
