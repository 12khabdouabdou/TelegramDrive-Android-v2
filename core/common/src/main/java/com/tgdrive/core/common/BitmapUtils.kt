package com.tgdrive.core.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Shared bitmap sampling, EXIF orientation normalization, and collage composition helpers.
 */
object BitmapUtils {

    fun computeInSampleSize(width: Int, height: Int, targetPx: Int): Int {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val safeTarget = targetPx.coerceAtLeast(1)
        var sample = 1
        while (safeW / sample > safeTarget || safeH / sample > safeTarget) {
            sample *= 2
        }
        return sample
    }

    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            rotated
        } catch (_: Throwable) {
            bitmap
        }
    }

    fun applyExifOrientationFromFile(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExifOrientation(bitmap, orientation)
    }

    fun applyExifOrientationFromBytes(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExifOrientation(bitmap, orientation)
    }

    fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
        if (src.width <= maxDim && src.height <= maxDim) return src
        val ratio = minOf(
            maxDim.toFloat() / src.width.coerceAtLeast(1),
            maxDim.toFloat() / src.height.coerceAtLeast(1),
        )
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /**
     * Composes 1..4 non-recycled bitmaps into a square collage (scaled single, 2-way split,
     * 1+2, or 2x2 grid). Caller retains ownership of input [bitmaps].
     */
    fun composeCollageBitmap(
        bitmaps: List<Bitmap>,
        sizePx: Int = 360,
    ): Bitmap? {
        val valid = ArrayList<Bitmap>(4)
        for (bmp in bitmaps) {
            if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                valid.add(bmp)
                if (valid.size >= 4) break
            }
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) {
            val single = valid[0]
            val ratio = minOf(
                sizePx.toFloat() / single.width.coerceAtLeast(1),
                sizePx.toFloat() / single.height.coerceAtLeast(1),
            )
            val w = (single.width * ratio).toInt().coerceIn(1, sizePx)
            val h = (single.height * ratio).toInt().coerceIn(1, sizePx)
            return Bitmap.createScaledBitmap(single, w, h, true)
        }

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(20, 24, 32))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val half = sizePx / 2
        val gap = 2
        val slots: List<Rect> = when (valid.size) {
            2 -> listOf(
                Rect(0, 0, half - gap / 2, sizePx),
                Rect(half + gap / 2, 0, sizePx, sizePx),
            )
            3 -> listOf(
                Rect(0, 0, half - gap / 2, half - gap / 2),
                Rect(half + gap / 2, 0, sizePx, half - gap / 2),
                Rect(0, half + gap / 2, sizePx, sizePx),
            )
            else -> listOf(
                Rect(0, 0, half - gap / 2, half - gap / 2),
                Rect(half + gap / 2, 0, sizePx, half - gap / 2),
                Rect(0, half + gap / 2, half - gap / 2, sizePx),
                Rect(half + gap / 2, half + gap / 2, sizePx, sizePx),
            )
        }
        for (i in slots.indices) {
            val bmp = valid[i]
            val dst = slots[i]
            val src = centerCropSrcRect(bmp.width, bmp.height, dst.width(), dst.height())
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        return out
    }

    private fun centerCropSrcRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (dstW <= 0 || dstH <= 0) return Rect(0, 0, srcW, srcH)
        val srcRatio = srcW.toFloat() / srcH.coerceAtLeast(1).toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            val cropW = (srcH * dstRatio).toInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstRatio).toInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }
    }
}
