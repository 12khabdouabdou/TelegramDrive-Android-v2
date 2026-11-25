package com.tgdrive.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen swipeable image viewer with bounded pinch-zoom, double-tap
 * zoom toggle, safe multi-touch vs pager arbitration, and IO image decoding.
 */
@Composable
fun ImageViewer(
    imagePaths: List<String>,
    initialPage: Int = 0,
    onDismiss: () -> Unit,
    onToggleChrome: (() -> Unit)? = null,
) {
    if (onToggleChrome == null) {
        BackHandler(onBack = onDismiss)
    }

    val safeInitial = initialPage.coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial) { imagePaths.size }
    var isCurrentZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        isCurrentZoomed = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCurrentZoomed && imagePaths.size > 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val path = imagePaths[page]
            ZoomableImagePage(
                path = path,
                isActive = page == pagerState.currentPage,
                onZoomChanged = { zoomed ->
                    if (page == pagerState.currentPage) {
                        isCurrentZoomed = zoomed
                    }
                },
                onTap = { onToggleChrome?.invoke() },
            )
        }

        // Standalone overlay close button when hosted without a caller chrome bar (e.g. ZipBrowser)
        if (onToggleChrome == null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun ZoomableImagePage(
    path: String,
    isActive: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    rotationDegrees: Float = 0f,
) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val animatedRotation by animateFloatAsState(targetValue = rotationDegrees, label = "photo_rotation")
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(isActive) {
        if (!isActive && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(path) {
        bitmap = null
        decodeFailed = false
        val decoded = withContext(Dispatchers.IO) { decodeSampledBitmap(path, 2048, 2048) }
        if (decoded != null) {
            bitmap = decoded
        } else {
            decodeFailed = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    },
                )
            }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        if (pointerCount >= 2 || scale > 1.02f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = ((containerSize.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                            scale = nextScale
                            offset = if (nextScale <= 1.02f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            onZoomChanged(nextScale > 1.05f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null && !bmp.isRecycled -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.player_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            rotationZ = animatedRotation,
                        ),
                )
            }
            decodeFailed -> {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = stringResource(R.string.player_image_error),
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            else -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private const val MAX_DECODED_BITMAP_BYTES = 32 * 1024 * 1024

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        val file = File(path)
        if (!file.isFile) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val (h, w) = opts.outHeight to opts.outWidth
        if (h <= 0 || w <= 0) return null
        var inSampleSize = 1
        while (h / inSampleSize > reqHeight || w / inSampleSize > reqWidth) {
            inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val scaledOrDecoded = if (
            decoded.width > reqWidth ||
            decoded.height > reqHeight ||
            decoded.allocationByteCount > MAX_DECODED_BITMAP_BYTES
        ) {
            val scaleFactor = minOf(
                reqWidth.toFloat() / decoded.width.coerceAtLeast(1).toFloat(),
                reqHeight.toFloat() / decoded.height.coerceAtLeast(1).toFloat(),
                1f,
            )
            val targetW = (decoded.width * scaleFactor).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scaleFactor).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            scaled
        } else {
            decoded
        }
        com.tgdrive.core.common.BitmapUtils.applyExifOrientationFromFile(path, scaledOrDecoded)
    } catch (_: Throwable) {
        null
    }
}
