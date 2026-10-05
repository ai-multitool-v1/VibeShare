package com.setbd.vibeshare.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.setbd.vibeshare.ui.theme.VibeColors

/**
 * Pulsing availability indicator. Communicates state with color AND an
 * accessible text label (never color alone; spec section 33).
 */
@Composable
fun PulsingDot(
    color: Color,
    label: String,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "pulseScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "pulseAlpha",
    )
    Box(
        modifier = modifier
            .size(size * 2)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 2)) {
            drawCircle(color = color.copy(alpha = alpha), radius = this.size.minDimension / 2 * scale / 1.8f + this.size.minDimension / 5f)
        }
        Box(
            Modifier
                .size(size)
                .background(color, CircleShape)
        )
    }
}

/**
 * Animated discovery radar: concentric expanding rings, subtle and lightweight.
 * Used on the Nearby Devices screen and Receive waiting state.
 */
@Composable
fun RadarView(
    modifier: Modifier = Modifier,
    ringColor: Color = VibeColors.Violet,
    content: @Composable () -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "radarProgress",
    )
    Box(modifier.semantics { contentDescription = "Searching for nearby devices" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val maxRadius = size.minDimension / 2f
            for (i in 0..2) {
                val p = (progress + i / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - p) * 0.35f),
                    radius = maxRadius * p,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        content()
    }
}

/** Connection-state chip with explicit text (accessibility-safe). */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
