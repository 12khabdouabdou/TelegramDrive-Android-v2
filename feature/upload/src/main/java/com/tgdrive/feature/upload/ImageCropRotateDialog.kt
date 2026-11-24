package com.tgdrive.feature.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interactive pre-upload crop and 90-degree rotation dialog for images queued in
 * [FileUploadReviewDialog]. Outputs a JPEG into `cacheDir/crop_tmp/` and returns its file URI.
 */
@Composable
fun ImageCropRotateDialog(
    sourceUri: Uri,
    displayName: String,
    onDismiss: () -> Unit,
    onResult: (editedUri: Uri, newSizeBytes: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rotationDeg by remember(sourceUri) { mutableFloatStateOf(0f) }
    var aspectRatio by remember(sourceUri) { mutableStateOf<Float?>(null) }
    var previewBitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri, rotationDeg, aspectRatio) {
        previewBitmap = withContext(Dispatchers.IO) {
            renderTransformedBitmap(
                context = context,
                uri = sourceUri,
                maxSidePx = 900,
                rotationDeg = rotationDeg,
                targetAspect = aspectRatio,
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.upload_crop_title),
                    style = TgTypography.Display.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = displayName,
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = previewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { rotationDeg = (rotationDeg - 90f + 360f) % 360f }) {
                        Icon(
                            Icons.Filled.RotateLeft,
                            contentDescription = stringResource(R.string.upload_crop_rotate_left),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${rotationDeg.toInt()}°",
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { rotationDeg = (rotationDeg + 90f) % 360f }) {
                        Icon(
                            Icons.Filled.RotateRight,
                            contentDescription = stringResource(R.string.upload_crop_rotate_right),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        null to stringResource(R.string.upload_crop_original),
                        1f to "1:1",
                        4f / 3f to "4:3",
                        16f / 9f to "16:9",
                    ).forEach { (ratio, label) ->
                        FilterChip(
                            selected = aspectRatio == ratio,
                            onClick = { aspectRatio = ratio },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            val fullBmp = renderTransformedBitmap(
                                context = context,
                                uri = sourceUri,
                                maxSidePx = 4096,
                                rotationDeg = rotationDeg,
                                targetAspect = aspectRatio,
                            ) ?: return@withContext null
                            try {
                                val cropDir = File(context.cacheDir, "crop_tmp").apply { mkdirs() }
                                val target = File(cropDir, "crop_${System.currentTimeMillis()}.jpg")
                                target.outputStream().use { out ->
                                    fullBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                                target
                            } finally {
                                fullBmp.recycle()
                            }
                        }
                        saving = false
                        if (outFile != null && outFile.isFile) {
                            onResult(Uri.fromFile(outFile), outFile.length())
                        } else {
                            onDismiss()
                        }
                    }
                },
            ) {
                Text(
                    if (saving) {
                        stringResource(R.string.upload_crop_saving)
                    } else {
                        stringResource(R.string.upload_crop_apply)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.upload_zip_cancel))
            }
        },
    )
}

private fun renderTransformedBitmap(
    context: Context,
    uri: Uri,
    maxSidePx: Int,
    rotationDeg: Float,
    targetAspect: Float?,
): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openUriStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxSidePx * 2 || bounds.outHeight / sample > maxSidePx * 2) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = openUriStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null

    var rotated: Bitmap? = null
    var cropped: Bitmap? = null
    try {
        val norm = ((rotationDeg % 360f) + 360f) % 360f
        val base = if (norm > 0.5f) {
            val m = Matrix().apply { postRotate(norm) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { rotated = it }
        } else {
            decoded
        }
        val finalBmp = if (targetAspect != null && targetAspect > 0f) {
            val w = base.width
            val h = base.height
            val curRatio = w.toFloat() / h.toFloat().coerceAtLeast(1f)
            val (cw, ch) = if (curRatio > targetAspect) {
                (h * targetAspect).toInt().coerceIn(1, w) to h
            } else {
                w to (w / targetAspect).toInt().coerceIn(1, h)
            }
            val x = ((w - cw) / 2).coerceAtLeast(0)
            val y = ((h - ch) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(base, x, y, cw, ch).also { cropped = it }
        } else {
            base
        }

        if (finalBmp === decoded) {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            finalBmp
        }
    } finally {
        if (rotated != null && rotated !== decoded && cropped !== rotated) {
            rotated?.recycle()
        }
        decoded.recycle()
    }
}.getOrNull()

private fun openUriStream(context: Context, uri: Uri) =
    if (uri.scheme == "file") {
        uri.path?.let { File(it).inputStream() }
    } else {
        context.contentResolver.openInputStream(uri)
    }
