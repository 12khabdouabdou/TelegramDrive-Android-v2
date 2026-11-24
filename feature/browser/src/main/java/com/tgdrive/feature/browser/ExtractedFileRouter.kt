package com.tgdrive.feature.browser

import com.tgdrive.core.common.isTextPreviewable

/**
 * Route for a file extracted from a ZIP archive or decrypted locally.
 *
 * - [ImageView] -> in-app zoomable image viewer
 * - [VideoView] -> in-app Media3 ExoPlayer video player
 * - [AudioPlay] -> in-app Media3 ExoPlayer persistent audio player bar
 * - [PdfView] -> in-app PdfRenderer viewer
 * - [TextView] -> in-app text/code/log viewer
 * - [External] -> fallback via [android.content.Intent.ACTION_VIEW] + FileProvider
 */
sealed interface ExtractedFileRoute {
    data class ImageView(
        val localPath: String,
    ) : ExtractedFileRoute

    data class VideoView(
        val localPath: String,
    ) : ExtractedFileRoute

    data class AudioPlay(
        val localPath: String,
        val fileName: String,
    ) : ExtractedFileRoute

    data class PdfView(
        val localPath: String,
        val fileName: String,
    ) : ExtractedFileRoute

    data class TextView(
        val localPath: String,
        val fileName: String,
    ) : ExtractedFileRoute

    data class External(
        val localPath: String,
        val mimeType: String,
    ) : ExtractedFileRoute
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "ts", "flv")
private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff")

/**
 * Decide how to open a file extracted from a ZIP or decrypted locally.
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
        ext in VIDEO_EXTS -> ExtractedFileRoute.VideoView(localPath)
        ext in AUDIO_EXTS -> ExtractedFileRoute.AudioPlay(localPath, fileName)
        ext == "pdf" -> ExtractedFileRoute.PdfView(localPath, fileName)
        isTextPreviewable(null, fileName) -> ExtractedFileRoute.TextView(localPath, fileName)
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
