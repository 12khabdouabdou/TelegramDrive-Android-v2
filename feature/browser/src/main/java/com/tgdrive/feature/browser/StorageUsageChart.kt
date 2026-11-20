package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.designsystem.theme.TgTypography

data class StorageSegment(
    val label: String,
    val bytes: Long,
    val color: Color,
)

@Composable
fun StorageUsageChart(items: List<FileCacheEntity>) {
    val segments = computeSegments(items)
    val totalBytes = segments.sumOf { it.bytes }

    if (totalBytes == 0L) return

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "Storage by type",
            style = TgTypography.Display.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            totalBytes.toHumanSize(),
            style = TgTypography.Mono,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
        )

        // Stacked bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(6.dp)),
        ) {
            segments.forEach { seg ->
                if (seg.bytes > 0 && totalBytes > 0) {
                    val fraction = seg.bytes.toFloat() / totalBytes.toFloat()
                    Box(
                        modifier =
                            Modifier
                                .weight(fraction)
                                .fillMaxHeight()
                                .background(seg.color),
                    )
                }
            }
        }

        // Legend
        segments.filter { it.bytes > 0 }.forEach { seg ->
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(seg.color))
                Text(
                    "  ${seg.label}: ${seg.bytes.toHumanSize()}",
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun computeSegments(items: List<FileCacheEntity>): List<StorageSegment> {
    val totals = mutableMapOf<com.tgdrive.core.common.FileBucket, Long>()
    for (item in items) {
        val bucket =
            com.tgdrive.core.common
                .classifyFile(item.mimeType, item.fileName)
        totals[bucket] = (totals[bucket] ?: 0L) + item.fileSize
    }
    return listOf(
        StorageSegment("Images", totals[com.tgdrive.core.common.FileBucket.IMAGES] ?: 0L, MaterialTheme.colorScheme.primary),
        StorageSegment("Videos", totals[com.tgdrive.core.common.FileBucket.VIDEOS] ?: 0L, Color(0xFFF5A623)),
        StorageSegment("Audio", totals[com.tgdrive.core.common.FileBucket.AUDIO] ?: 0L, MaterialTheme.colorScheme.tertiary),
        StorageSegment(
            "Documents",
            (totals[com.tgdrive.core.common.FileBucket.PDF] ?: 0L) +
                (totals[com.tgdrive.core.common.FileBucket.DOCUMENTS] ?: 0L),
            MaterialTheme.colorScheme.error,
        ),
        StorageSegment("Archives", totals[com.tgdrive.core.common.FileBucket.ARCHIVES] ?: 0L, MaterialTheme.colorScheme.secondary),
        StorageSegment("Other", totals[com.tgdrive.core.common.FileBucket.OTHER] ?: 0L, MaterialTheme.colorScheme.onSurfaceVariant),
    )
}
