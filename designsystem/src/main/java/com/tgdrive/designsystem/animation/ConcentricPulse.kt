package com.tgdrive.designsystem.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated concentric pulse — renders N expanding rings with staggered start offsets.
 */
@Composable
fun ConcentricPulse(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
    maxRadius: Dp = 28.dp,
    durationMs: Int = 1600,
    rings: Int = 3,
    coreRadius: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulse")

    val phases = (0 until rings).map { i ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = durationMs,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(
                    offsetMillis = durationMs * i / rings,
                    offsetType = StartOffsetType.FastForward,
                ),
            ),
            label = "ring-$i",
        )
    }

    Box(
        modifier = modifier.size((maxRadius + coreRadius) * 2),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size((maxRadius + coreRadius) * 2)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val corePx = coreRadius.toPx()
            val maxPx = maxRadius.toPx()

            // Solid core
            drawCircle(
                color = color,
                radius = corePx,
                center = center,
            )

            // Expanding rings
            phases.forEach { phase ->
                val p = phase.value
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.6f),
                    radius = corePx + (maxPx - corePx) * p,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
