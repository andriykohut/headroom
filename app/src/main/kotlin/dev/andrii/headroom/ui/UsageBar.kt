package dev.andrii.headroom.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The three states a bar can be in. Colour is never the only thing that differs. */
enum class BarState { FINE, APPROACHING, WALL }

fun barState(percentUsed: Double, thresholdPercent: Double): BarState = when {
    percentUsed >= 100.0 -> BarState.WALL
    percentUsed >= thresholdPercent -> BarState.APPROACHING
    else -> BarState.FINE
}

/**
 * The meter.
 *
 * Drawn by hand rather than with `LinearProgressIndicator` because neither the
 * notch nor the hatch can be expressed through that component's parameters.
 *
 * The notch is always drawn, whatever the trigger toggles say: it marks where
 * the user's own warning line sits, so crossing it is legible as geometry
 * rather than only as a colour change. That is what lets the three states
 * survive greyscale, a glance, and colour-blindness.
 */
@Composable
fun UsageBar(
    percentUsed: Double,
    thresholdPercent: Double,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    dimmed: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val state = barState(percentUsed, thresholdPercent)

    val fraction by animateFloatAsState(
        targetValue = (percentUsed / 100.0).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "fill",
    )

    val alpha = if (dimmed) DIMMED_ALPHA else 1f
    val trackColor = when (state) {
        BarState.WALL -> colors.errorContainer
        else -> colors.surfaceContainerHighest
    }
    val fillColor = when (state) {
        BarState.FINE -> colors.primary
        BarState.APPROACHING -> colors.tertiary
        BarState.WALL -> Color.Transparent
    }
    val hatchColor = colors.error
    val notchColor = colors.surface
    val height: Dp = if (hero) HERO_HEIGHT else COMPACT_HEIGHT

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        val rounded = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = size.height,
                    cornerRadius = radius,
                ),
            )
        }

        drawRoundRect(color = trackColor.copy(alpha = alpha), cornerRadius = radius)

        if (state == BarState.WALL) {
            // Blocked off edge to edge. The hatch is the part that reads with
            // the colour removed: at the wall the texture changes, not just
            // the hue.
            clipPath(rounded) {
                val pitch = HATCH_PITCH.toPx()
                val stroke = HATCH_STROKE.toPx()
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = hatchColor.copy(alpha = alpha),
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = stroke,
                    )
                    x += pitch
                }
            }
        } else if (fraction > 0f) {
            clipPath(rounded) {
                drawRoundRect(
                    color = fillColor.copy(alpha = alpha),
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }

        // Cut last, through everything, so the warning line is never hidden by
        // a fill that has passed it.
        val notchWidth = NOTCH_WIDTH.toPx()
        val notchX = size.width * (thresholdPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
        drawRect(
            color = notchColor,
            topLeft = Offset(notchX - notchWidth / 2f, 0f),
            size = Size(notchWidth, size.height),
        )
    }
}

private val HERO_HEIGHT = 12.dp
private val COMPACT_HEIGHT = 8.dp
private val NOTCH_WIDTH = 2.dp
private val HATCH_STROKE = 2.dp
private val HATCH_PITCH = 8.dp
const val DIMMED_ALPHA = 0.38f
