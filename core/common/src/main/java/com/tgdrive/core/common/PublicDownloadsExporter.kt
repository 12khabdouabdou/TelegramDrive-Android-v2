package com.tgdrive.core.common

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Shared public `Downloads/TelegramDrive/` MediaStore & legacy exporter plus safe filename/MIME resolution.
 */
object PublicDownloadsExporter {

    fun sanitizeFileName(raw: String, fallback: String = "download"): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
        return cleaned.ifBlank { fallback }
    }

    fun sanitizeSubDirectory(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val illegal = Regex("[/:*?\"<>|\\u0000-\\u001F]")
        return raw.split('/', '\\')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.replace(illegal, "_").trim(' ', '.') }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    fun resolveMimeType(fileName: String, explicitMime: String? = null): String {
        val trimmed = explicitMime?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != "application/octet-stream" && trimmed != "*/*") {
            return trimmed
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            else -> runCatching {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            }.getOrNull() ?: trimmed.ifEmpty { "application/octet-stream" }
        }
    }

    fun saveStreamToPublicDownloads(
        ctx: Context,
        displayName: String,
        mimeType: String? = null,
        subDirectory: String? = null,
        writer: (OutputStream) -> Boolean,
    ): Boolean {
        val safeName = sanitizeFileName(displayName)
        val mime = resolveMimeType(safeName, mimeType)
        val cleanSub = sanitizeSubDirectory(subDirectory)
        val relFolder = if (cleanSub.isEmpty()) "TelegramDrive" else "TelegramDrive/$cleanSub"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$relFolder")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                try {
                    val ok = resolver.openOutputStream(uri)?.use { out -> writer(out) }
                        ?: throw IllegalStateException("Could not open MediaStore Downloads stream")
                    if (!ok) {
                        runCatching { resolver.delete(uri, null, null) }
                        return@runCatching false
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    relFolder,
                ).apply { mkdirs() }
                val dest = File(dir, safeName)
                val ok = try {
                    FileOutputStream(dest).use { out -> writer(out) }
                } catch (t: Throwable) {
                    runCatching { dest.delete() }
                    throw t
                }
                if (!ok) {
                    runCatching { dest.delete() }
                }
                ok
            }
        }.getOrDefault(false)
    }

    fun saveToPublicDownloads(
        ctx: Context,
        source: File,
        displayName: String,
        mimeType: String? = null,
        subDirectory: String? = null,
    ): File? {
        if (!source.isFile || source.length() <= 0L) return null
        val safeName = sanitizeFileName(displayName, fallback = source.name.ifBlank { "download" })
        val mime = resolveMimeType(safeName, mimeType)
        val cleanSub = sanitizeSubDirectory(subDirectory)
        val relFolder = if (cleanSub.isEmpty()) "TelegramDrive" else "TelegramDrive/$cleanSub"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ok = saveStreamToPublicDownloads(
                    ctx = ctx,
                    displayName = safeName,
                    mimeType = mime,
                    subDirectory = cleanSub,
                ) { out ->
                    FileInputStream(source).use { input -> input.copyTo(out, 64 * 1024) }
                    true
                }
                if (ok) source else null
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    relFolder,
                ).apply { mkdirs() }
                val dest = File(dir, safeName)
                source.copyTo(dest, overwrite = true)
                dest
            }
        }.getOrNull()
    }
}
