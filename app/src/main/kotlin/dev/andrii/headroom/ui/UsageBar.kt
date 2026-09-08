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

/** The states a bar can be in. Colour is never the only thing that differs. */
enum class BarState { FINE, APPROACHING, WALL, RESET }

/**
 * [hasReset] outranks the percentage because it invalidates it: the window that
 * figure described has ended, so drawing 100% as the wall would announce a
 * limit that has already lifted.
 */
fun barState(
    percentUsed: Double,
    thresholdPercent: Double,
    hasReset: Boolean = false,
): BarState = when {
    hasReset -> BarState.RESET
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
 * rather than only as a colour change. That is what lets the three
 * utilisation states
 * survive greyscale, a glance, and colour-blindness.
 */
@Composable
fun UsageBar(
    percentUsed: Double,
    thresholdPercent: Double,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    dimmed: Boolean = false,
    hasReset: Boolean = false,
    paceFraction: Double? = null,
) {
    val colors = MaterialTheme.colorScheme
    val state = barState(percentUsed, thresholdPercent, hasReset)

    val fraction by animateFloatAsState(
        // An ended window has no fill to show: the figure belongs to a window
        // that is over, and the new one is unmeasured until the next push.
        targetValue = if (state == BarState.RESET) 0f
        else (percentUsed / 100.0).coerceIn(0.0, 1.0).toFloat(),
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
        BarState.WALL, BarState.RESET -> Color.Transparent
    }
    val hatchColor = colors.error
    val notchColor = colors.surface
    val paceColor = colors.onSurfaceVariant
    val height: Dp = if (hero) HERO_HEIGHT else COMPACT_HEIGHT

    // Room beneath the track for the pace mark, and only when one is drawn:
    // without it the mark would be clipped by the canvas it sits under.
    val paceRoom = if (paceFraction != null) PACE_GAP + PACE_HEIGHT else 0.dp

    Canvas(modifier = modifier.fillMaxWidth().height(height + paceRoom)) {
        val trackHeight = size.height - paceRoom.toPx()
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val rounded = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = trackHeight,
                    cornerRadius = radius,
                ),
            )
        }

        drawRoundRect(
            color = trackColor.copy(alpha = alpha),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )

        if (state == BarState.WALL) {
            // Blocked off edge to edge. The hatch is the part that reads with
            // the colour removed: at the wall the texture changes, not just
            // the hue.
            clipPath(rounded) {
                val pitch = HATCH_PITCH.toPx()
                val stroke = HATCH_STROKE.toPx()
                var x = -trackHeight
                while (x < size.width + trackHeight) {
                    drawLine(
                        color = hatchColor.copy(alpha = alpha),
                        start = Offset(x, trackHeight),
                        end = Offset(x + trackHeight, 0f),
                        strokeWidth = stroke,
                    )
                    x += pitch
                }
            }
        } else if (fraction > 0f) {
            clipPath(rounded) {
                drawRoundRect(
                    color = fillColor.copy(alpha = alpha),
                    size = Size(size.width * fraction, trackHeight),
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
            size = Size(notchWidth, trackHeight),
        )

        // Under the track, never cut into it: the notch is the user's own
        // warning line and must stay the only thing that interrupts the bar.
        // Fill past this mark means spending faster than an even pace.
        paceFraction?.let {
            val x = size.width * it.coerceIn(0.0, 1.0).toFloat()
            drawRect(
                color = paceColor.copy(alpha = alpha),
                topLeft = Offset(x - notchWidth / 2f, trackHeight + PACE_GAP.toPx()),
                size = Size(notchWidth, PACE_HEIGHT.toPx()),
            )
        }
    }
}

private val HERO_HEIGHT = 12.dp
private val COMPACT_HEIGHT = 8.dp
private val NOTCH_WIDTH = 2.dp
private val HATCH_STROKE = 2.dp
private val HATCH_PITCH = 8.dp
private val PACE_GAP = 3.dp
private val PACE_HEIGHT = 3.dp
const val DIMMED_ALPHA = 0.38f
