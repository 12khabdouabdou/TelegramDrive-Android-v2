package com.tgdrive.core.common

/**
 * Canonical file-type classification — the single source of truth for
 * "what kind of file is this?" across the app.
 *
 * Used by:
 *   - [com.tgdrive.feature.browser.FilePreviewRouter] — which player to open
 *   - [com.tgdrive.feature.browser.FolderViewModel.applyFilter] — filter chips
 *   - [com.tgdrive.feature.browser.StorageUsageChart] — stacked-bar segments
 *   - [com.tgdrive.feature.browser.analytics.StorageAnalytics] — per-type breakdown
 *
 * Replaces 4 duplicated `mime.startsWith(...) || ext in listOf(...)` blocks
 * that had drifted (HEIC in one copy, .opus in another, etc.).
 *
 * Resolution order: MIME prefix first (more reliable when TDLib reports it),
 * then file extension fallback (covers files whose MIME is missing or
 * `application/octet-stream`).
 */
enum class FileBucket(val label: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    PDF("PDF"),
    DOCUMENTS("Documents"),
    ARCHIVES("Archives"),
    OTHER("Other"),
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp")
private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus")
private val DOC_EXTS = setOf("txt", "doc", "docx", "rtf", "odt", "md", "csv", "xls", "xlsx", "ppt", "pptx")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val TEXT_PREVIEW_EXTS = setOf(
    "txt", "md", "markdown", "json", "log", "csv", "xml", "yaml", "yml",
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "sh", "bash",
    "ini", "conf", "cfg", "toml", "gradle", "properties", "html", "htm",
    "css", "c", "cpp", "cc", "h", "hpp", "rs", "go", "sql", "env",
)

fun isTextPreviewable(mimeType: String?, fileName: String): Boolean {
    val mime = mimeType?.lowercase().orEmpty()
    if (mime.startsWith("text/") ||
        mime == "application/json" ||
        mime == "application/xml" ||
        mime == "application/x-yaml"
    ) {
        return true
    }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in TEXT_PREVIEW_EXTS
}

/**
 * Classify a file by its MIME type and/or extension into a [FileBucket].
 *
 * Either parameter may be null/empty — the function falls back to whichever
 * signal is present. Both signals are matched case-insensitively.
 */
fun classifyFile(mimeType: String?, fileNameOrExt: String): FileBucket {
    val mime = mimeType?.lowercase().orEmpty()
    if (mime.isNotEmpty() && mime != "application/octet-stream") {
        when {
            mime.startsWith("image/") -> return FileBucket.IMAGES
            mime.startsWith("video/") -> return FileBucket.VIDEOS
            mime.startsWith("audio/") -> return FileBucket.AUDIO
            mime == "application/pdf" -> return FileBucket.PDF
        }
    }
    val ext = fileNameOrExt.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTS -> FileBucket.IMAGES
        ext in VIDEO_EXTS -> FileBucket.VIDEOS
        ext in AUDIO_EXTS -> FileBucket.AUDIO
        ext == "pdf" -> FileBucket.PDF
        ext in DOC_EXTS -> FileBucket.DOCUMENTS
        ext in ARCHIVE_EXTS -> FileBucket.ARCHIVES
        else -> FileBucket.OTHER
    }
}
