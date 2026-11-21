package com.tgdrive.feature.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tgdrive.core.common.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and caches first-page JPEG thumbnails for locally available PDF files
 * inside `cacheDir/pdf_thumbs/` so folder grids/lists show crisp visual previews.
 */
@Singleton
class PdfThumbnailCache @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dispatchers: DispatchersProvider,
) {
    private val thumbsDir: File
        get() = File(ctx.cacheDir, "pdf_thumbs").apply { mkdirs() }

    private val mutex = Mutex()

    fun getCachedThumbnailPath(chatId: Long, messageId: Long): String? {
        val file = File(thumbsDir, "${chatId}_$messageId.jpg")
        return file.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    suspend fun getOrCreateThumbnail(
        chatId: Long,
        messageId: Long,
        localPdfPath: String,
    ): String? = withContext(dispatchers.io) {
        val existing = getCachedThumbnailPath(chatId, messageId)
        if (existing != null) return@withContext existing

        val pdfFile = File(localPdfPath)
        if (!pdfFile.isFile || pdfFile.length() <= 0L) return@withContext null

        mutex.withLock {
            val rechecked = getCachedThumbnailPath(chatId, messageId)
            if (rechecked != null) return@withLock rechecked

            val outFile = File(thumbsDir, "${chatId}_$messageId.jpg")
            val tmpFile = File(thumbsDir, "${chatId}_$messageId.tmp")
            runCatching {
                val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                fd.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount <= 0) return@runCatching null
                        renderer.openPage(0).use { page ->
                            val targetW = 360
                            val ratio = targetW.toFloat() / page.width.coerceAtLeast(1).toFloat()
                            val targetH = (page.height * ratio).toInt().coerceIn(120, 640)
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bmp)
                                canvas.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                FileOutputStream(tmpFile).use { fos ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                                }
                            } finally {
                                if (!bmp.isRecycled) bmp.recycle()
                            }
                        }
                    }
                }
                if (tmpFile.exists() && tmpFile.length() > 0L) {
                    if (outFile.exists()) outFile.delete()
                    if (!tmpFile.renameTo(outFile)) {
                        tmpFile.copyTo(outFile, overwrite = true)
                        tmpFile.delete()
                    }
                    outFile.absolutePath
                } else {
                    tmpFile.delete()
                    null
                }
            }.getOrElse {
                tmpFile.delete()
                null
            }
        }
    }
}
