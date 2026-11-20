package com.tgdrive.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgdrive.designsystem.theme.TgTypography
import java.util.Locale

/**
 * Pie chart + legend for storage breakdown by file type.
 */
@Composable
fun StoragePieChart(
    segments: List<PieSegment>,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val active = segments.filter { it.bytes > 0 }
    val total = active.sumOf { it.bytes }.coerceAtLeast(1)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryText = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (active.isEmpty() || totalBytes == 0L) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(50))
                        .background(trackColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No data", style = TgTypography.Mono, color = mutedColor)
                }
            } else {
                val chartDesc = active.joinToString(", ") { "${it.label} ${it.bytes.toHumanSize()}" }
                Canvas(
                    modifier = Modifier
                        .size(180.dp)
                        .semantics { contentDescription = "Storage breakdown: $chartDesc" },
                ) {
                    val stroke = Stroke(width = 32.dp.toPx())
                    val diameter = minOf(size.width, size.height) - stroke.width
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f,
                    )
                    val arcSize = Size(diameter, diameter)

                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )

                    var start = -90f
                    for (seg in active) {
                        val sweep = (seg.bytes.toFloat() / total.toFloat()) * 360f
                        drawArc(
                            color = seg.color,
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke,
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        totalBytes.toHumanSize(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText,
                    )
                    Text("total", fontSize = 12.sp, color = mutedColor)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            active.forEach { seg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(seg.color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${seg.label}: ${seg.bytes.toHumanSize()}",
                        style = TgTypography.Display.bodySmall,
                        color = mutedColor,
                    )
                }
            }
        }
    }
}

data class PieSegment(
    val label: String,
    val bytes: Long,
    val color: Color,
)

private fun Long.toHumanSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(toDouble()) / Math.log10(1024.0)).toInt()
        .coerceAtMost(units.lastIndex)
    val value = this / Math.pow(1024.0, digitGroups.toDouble())
    val formatted = if (digitGroups == 0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    }
    return "$formatted ${units[digitGroups]}"
}
