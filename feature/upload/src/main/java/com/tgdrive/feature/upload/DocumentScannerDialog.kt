package com.tgdrive.feature.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Multi-page camera / gallery document scanner dialog that renders pages into an A4 PDF
 * inside `cacheDir/scan_tmp/` with an optional high-contrast B&W scan filter.
 */
@Composable
fun DocumentScannerDialog(
    onDismiss: () -> Unit,
    onPdfReady: (pdfUri: Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<Uri>() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var enhanceContrast by remember { mutableStateOf(true) }
    var pdfName by remember {
        mutableStateOf("scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
    }
    var generating by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val noCameraMsg = stringResource(R.string.upload_scan_no_camera)
    val buildFailedMsg = stringResource(R.string.upload_scan_failed)

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { saved ->
        val captured = pendingCameraUri
        if (saved && captured != null) {
            pages.add(captured)
            errorMsg = null
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { picked ->
        if (picked.isNotEmpty()) {
            pages.addAll(picked)
            errorMsg = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DocumentScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.upload_scan_title),
                    style = TgTypography.Display.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !generating,
                        onClick = {
                            runCatching {
                                val scanDir = File(context.cacheDir, "scan_tmp").apply { mkdirs() }
                                val outFile = File(scanDir, "page_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            }.onFailure {
                                errorMsg = noCameraMsg
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.upload_scan_camera), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        enabled = !generating,
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.upload_scan_gallery),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (pages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.upload_scan_pages_empty),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(vertical = 4.dp),
                    ) {
                        itemsIndexed(pages) { idx, _ ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.upload_scan_page_label, idx + 1),
                                    style = TgTypography.Display.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    enabled = idx > 0 && !generating,
                                    onClick = {
                                        val moved = pages.removeAt(idx)
                                        pages.add(idx - 1, moved)
                                    },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                                }
                                IconButton(
                                    enabled = idx < pages.lastIndex && !generating,
                                    onClick = {
                                        val moved = pages.removeAt(idx)
                                        pages.add(idx + 1, moved)
                                    },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                                }
                                IconButton(
                                    enabled = !generating,
                                    onClick = { pages.removeAt(idx) },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = pdfName,
                    onValueChange = { pdfName = it },
                    label = { Text(stringResource(R.string.upload_scan_name_label)) },
                    suffix = { Text(".pdf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = enhanceContrast,
                            role = Role.Switch,
                            onValueChange = { enhanceContrast = it },
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.upload_scan_enhance),
                            style = TgTypography.Display.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.upload_scan_enhance_desc),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enhanceContrast, onCheckedChange = null)
                }

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        style = TgTypography.Caption,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pages.isNotEmpty() && !generating,
                onClick = {
                    generating = true
                    errorMsg = null
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            buildPdfFromPages(
                                context = context,
                                pageUris = pages.toList(),
                                baseName = pdfName,
                                enhanceBw = enhanceContrast,
                            )
                        }
                        generating = false
                        if (outFile != null && outFile.isFile) {
                            onPdfReady(Uri.fromFile(outFile))
                        } else {
                            errorMsg = buildFailedMsg
                        }
                    }
                },
            ) {
                Text(
                    if (generating) {
                        stringResource(R.string.upload_scan_creating)
                    } else {
                        stringResource(R.string.upload_scan_create)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !generating, onClick = onDismiss) {
                Text(stringResource(R.string.upload_zip_cancel))
            }
        },
    )
}

private fun buildPdfFromPages(
    context: Context,
    pageUris: List<Uri>,
    baseName: String,
    enhanceBw: Boolean,
): File? = runCatching {
    val cleanBase = baseName.trim()
        .removeSuffix(".pdf")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "scan_${System.currentTimeMillis()}" }
    val scanDir = File(context.cacheDir, "scan_tmp").apply { mkdirs() }
    val outFile = File(scanDir, "$cleanBase.pdf")

    val pageW = 595
    val pageH = 842
    val margin = 20f
    val doc = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (enhanceBw) {
        val bwMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.45f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        bwMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(bwMatrix)
    }

    var pageNum = 1
    try {
        for (uri in pageUris) {
            val bmp = decodeBoundedScanBitmap(context, uri, 1800) ?: continue
            try {
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum++).create()
                val page = doc.startPage(info)
                val canvas: Canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val availW = pageW - margin * 2f
                val availH = pageH - margin * 2f
                val scale = minOf(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val left = (pageW - drawW) / 2f
                val top = (pageH - drawH) / 2f
                val dst = android.graphics.RectF(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(bmp, null, dst, paint)
                doc.finishPage(page)
            } finally {
                bmp.recycle()
            }
        }
        if (pageNum == 1) return null
        outFile.outputStream().use { doc.writeTo(it) }
        outFile
    } finally {
        doc.close()
    }
}.getOrNull()

private fun decodeBoundedScanBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val openStream: () -> java.io.InputStream? = {
        if (uri.scheme == "file") uri.path?.let { File(it).inputStream() } else context.contentResolver.openInputStream(uri)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()
