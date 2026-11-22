package app.openhikes.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.ui.theme.parseHexColorOrDefault

private val DefaultSwatchWidth = 44.dp
private val DefaultSwatchHeight = 18.dp
private val SwatchStrokeWidth = 4.dp
private val SwatchHorizontalInset = 4.dp
private const val CHEVRON_DARK_THRESHOLD = 0.6f
private const val CHEVRON_DARK_ALPHA = 0.9f
private const val CHEVRON_LIGHT_GRAY = 0.15f
private const val DIRECTIONAL_CHEVRON_COUNT = 3
private const val ARROWHEAD_CHEVRON_COUNT = 4
private const val CHEVRON_REACH_FACTOR = 0.9f
private const val CHEVRON_SPREAD_FACTOR = 0.9f
private const val CHEVRON_STROKE_FACTOR = 0.45f
private const val ARROWHEAD_SCALE = 1.45f
private const val DASH_STROKE_MULTIPLIER = 2.6f
private const val DASH_GAP_MULTIPLIER = 2.0f
private const val DOT_GAP_MULTIPLIER = 1.8f

/**
 * Draws a live preview swatch of a [RouteLinePattern] using the same tint, border,
 * and chevron contrast rules as the map route overlay.
 */
@Composable
fun RoutePatternSwatch(pattern: RouteLinePattern, tintHex: String, borderHex: String, modifier: Modifier = Modifier) {
    val tintColor = parseHexColorOrDefault(tintHex, Color(0xFF1B7F3B))
    val hasBorder = RouteBorder.isVisible(borderHex)
    val borderColor = parseHexColorOrDefault(RouteBorder.resolvedBorderHex(borderHex), Color.Black)

    Canvas(modifier = modifier.size(DefaultSwatchWidth, DefaultSwatchHeight)) {
        val inset = SwatchHorizontalInset.toPx()
        val startX = inset
        val endX = (size.width - inset).coerceAtLeast(startX + 1f)
        val centerY = size.height / 2f
        val strokePx = SwatchStrokeWidth.toPx()
        val borderStrokePx = strokePx + RouteBorder.width(SwatchStrokeWidth.value.toDouble()).toFloat() * 2f * density

        if (pattern.drawsLine) {
            drawPatternLine(
                LinePatternSpec(
                    startX = startX,
                    endX = endX,
                    centerY = centerY,
                    strokePx = strokePx,
                    borderStrokePx = borderStrokePx,
                    pattern = pattern,
                    tintColor = tintColor,
                    borderColor = if (hasBorder) borderColor else null
                )
            )
        }

        if (pattern.drawsChevrons) {
            val scale = if (pattern == RouteLinePattern.ARROWHEADS) ARROWHEAD_SCALE else 1f
            val spec = ChevronSpec(
                startX = startX,
                endX = endX,
                centerY = centerY,
                count = if (pattern == RouteLinePattern.ARROWHEADS) {
                    ARROWHEAD_CHEVRON_COUNT
                } else {
                    DIRECTIONAL_CHEVRON_COUNT
                },
                reach = strokePx * CHEVRON_REACH_FACTOR * scale,
                spread = strokePx * CHEVRON_SPREAD_FACTOR * scale,
                strokeWidth = strokePx * CHEVRON_STROKE_FACTOR * scale,
                color = resolveChevronColor(pattern, tintColor),
                borderColor = if (pattern.chevronsUseRouteTint && hasBorder) borderColor else null
            )
            drawChevrons(spec)
        }
    }
}

private data class ChevronSpec(
    val startX: Float,
    val endX: Float,
    val centerY: Float,
    val count: Int,
    val reach: Float,
    val spread: Float,
    val strokeWidth: Float,
    val color: Color,
    val borderColor: Color?
)

private data class LinePatternSpec(
    val startX: Float,
    val endX: Float,
    val centerY: Float,
    val strokePx: Float,
    val borderStrokePx: Float,
    val pattern: RouteLinePattern,
    val tintColor: Color,
    val borderColor: Color?
)

private fun DrawScope.drawPatternLine(spec: LinePatternSpec) {
    val cap = if (spec.pattern.usesRoundCap) StrokeCap.Round else StrokeCap.Butt
    val pathEffect = when (spec.pattern) {
        RouteLinePattern.DASHED -> PathEffect.dashPathEffect(
            floatArrayOf(spec.strokePx * DASH_STROKE_MULTIPLIER, spec.strokePx * DASH_GAP_MULTIPLIER),
            0f
        )
        RouteLinePattern.DOTTED -> PathEffect.dashPathEffect(
            floatArrayOf(0.5f, spec.strokePx * DOT_GAP_MULTIPLIER),
            0f
        )
        else -> null
    }
    if (spec.borderColor != null) {
        drawLine(
            color = spec.borderColor,
            start = Offset(spec.startX, spec.centerY),
            end = Offset(spec.endX, spec.centerY),
            strokeWidth = spec.borderStrokePx,
            cap = cap,
            pathEffect = pathEffect
        )
    }
    drawLine(
        color = spec.tintColor,
        start = Offset(spec.startX, spec.centerY),
        end = Offset(spec.endX, spec.centerY),
        strokeWidth = spec.strokePx,
        cap = cap,
        pathEffect = pathEffect
    )
}

private fun resolveChevronColor(pattern: RouteLinePattern, tintColor: Color): Color {
    if (pattern.chevronsUseRouteTint) return tintColor
    val isLight = tintColor.luminance() > CHEVRON_DARK_THRESHOLD
    return if (isLight) {
        Color(CHEVRON_LIGHT_GRAY, CHEVRON_LIGHT_GRAY, CHEVRON_LIGHT_GRAY, CHEVRON_DARK_ALPHA)
    } else {
        Color.White.copy(alpha = CHEVRON_DARK_ALPHA)
    }
}

private fun DrawScope.drawChevrons(spec: ChevronSpec) {
    val span = spec.endX - spec.startX
    for (index in 1..spec.count) {
        val x = spec.startX + span * (index.toFloat() / (spec.count + 1))
        val path = Path().apply {
            moveTo(x - spec.reach * 0.5f, spec.centerY - spec.spread * 0.5f)
            lineTo(x + spec.reach * 0.5f, spec.centerY)
            lineTo(x - spec.reach * 0.5f, spec.centerY + spec.spread * 0.5f)
        }
        if (spec.borderColor != null) {
            drawPath(
                path = path,
                color = spec.borderColor,
                style = Stroke(width = spec.strokeWidth * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        drawPath(
            path = path,
            color = spec.color,
            style = Stroke(width = spec.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
