package me.crsib.freecartracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.crsib.freecartracker.ui.util.formatDuration
import me.crsib.freecartracker.ui.util.formatNumber
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One plotted sample: a value at a wall-clock time, so the chart can show "time into trip". */
data class ChartPoint(val timeMillis: Long, val value: Float)

/**
 * Interactive line chart drawn on a Canvas — no chart dependency, keeps the APK small.
 *
 * Gestures:
 * - **Pinch** to zoom the time axis; **two-finger drag** to pan while zoomed.
 * - **Tap / drag** with one finger to scrub a cursor that reads off the value and the
 *   elapsed time at that point.
 *
 * The value range (min/max of the whole series) is always labelled on the left axis.
 */
@Composable
fun TripChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    unit: String = "",
    lineColor: Color = Color(0xFF1565C0),
    valueFormat: (Float) -> String = { formatNumber(it.toDouble()) },
) {
    if (points.size < 2) return

    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val n = points.size
    val startMillis = points.first().timeMillis

    // Viewport over the normalized time domain [0, 1]. scale >= 1 zooms in; pan is the
    // left edge of the visible window, in domain fractions, clamped so we never scroll past the ends.
    var scale by remember(points) { mutableFloatStateOf(1f) }
    var pan by remember(points) { mutableFloatStateOf(0f) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val maxZoom = 40f
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.primary
    val haloColor = MaterialTheme.colorScheme.surface
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipText = MaterialTheme.colorScheme.inverseOnSurface
    val measurer = rememberTextMeasurer()

    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .pointerInput(points) {
                    awaitEachGesture {
                        val leftAxis = 44.dp.toPx()
                        val plotW = (size.width - leftAxis).coerceAtLeast(1f)

                        fun indexAt(x: Float): Int {
                            val frac = pan + ((x - leftAxis) / plotW) / scale
                            return (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        }

                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multiTouch = false
                        if (down.position.x >= leftAxis) selected = indexAt(down.position.x)

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                multiTouch = true
                                selected = null
                                val zoom = event.calculateZoom()
                                val panChange = event.calculatePan().x
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f || panChange != 0f) {
                                    val cx = ((centroid.x - leftAxis) / plotW).coerceIn(0f, 1f)
                                    val fracUnder = pan + cx / scale
                                    scale = (scale * zoom).coerceIn(1f, maxZoom)
                                    pan = (fracUnder - cx / scale - (panChange / plotW) / scale)
                                        .coerceIn(0f, 1f - 1f / scale)
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (pressed.size == 1 && !multiTouch) {
                                val c = pressed.first()
                                if (c.position.x >= leftAxis) {
                                    selected = indexAt(c.position.x)
                                    c.consume()
                                }
                            }
                        } while (pressed.any { it.pressed })
                    }
                },
        ) {
            val leftAxis = 44.dp.toPx()
            val topPad = 10.dp.toPx()
            val botPad = 10.dp.toPx()
            val plotLeft = leftAxis
            val plotRight = size.width
            val plotTop = topPad
            val plotBottom = size.height - botPad
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            fun xForIndex(i: Int): Float {
                val frac = i.toFloat() / (n - 1)
                return plotLeft + ((frac - pan) * scale) * plotW
            }
            fun yForValue(v: Float): Float = plotBottom - ((v - min) / range) * plotH

            // Horizontal gridlines + value-range labels (max on top, min on bottom, mid in between).
            val gridValues = listOf(max, (max + min) / 2f, min)
            gridValues.forEach { gv ->
                val y = yForValue(gv)
                drawLine(axisColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                val label = measurer.measure(valueFormat(gv), axisLabelStyle(labelColor))
                drawText(
                    label,
                    topLeft = Offset(
                        plotLeft - label.size.width - 4.dp.toPx(),
                        (y - label.size.height / 2f).coerceIn(0f, size.height - label.size.height),
                    ),
                )
            }

            // The line: only the visible index window, so deep zoom stays cheap.
            val firstIdx = floor(pan * (n - 1)).toInt().coerceIn(0, n - 1)
            val lastIdx = ceil((pan + 1f / scale) * (n - 1)).toInt().coerceIn(0, n - 1)
            val path = Path().apply {
                moveTo(xForIndex(firstIdx), yForValue(points[firstIdx].value))
                for (i in firstIdx + 1..lastIdx) lineTo(xForIndex(i), yForValue(points[i].value))
            }
            drawPath(path, color = lineColor, style = Stroke(width = 3.dp.toPx()))

            // Scrub cursor + tooltip.
            val sel = selected
            if (sel != null && sel in firstIdx..lastIdx) {
                val p = points[sel]
                val x = xForIndex(sel).coerceIn(plotLeft, plotRight)
                val y = yForValue(p.value)
                // Subtle dashed cursor so it reads as a guide, not another data line.
                drawLine(
                    cursorColor.copy(alpha = 0.45f),
                    Offset(x, plotTop), Offset(x, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                // Highlighted point: a card-coloured halo ring lifts the dot off the line.
                drawCircle(haloColor, radius = 6.dp.toPx(), center = Offset(x, y))
                drawCircle(cursorColor, radius = 4.dp.toPx(), center = Offset(x, y))
                drawTooltip(
                    measurer = measurer,
                    valueText = if (unit.isEmpty()) valueFormat(p.value) else "${valueFormat(p.value)} $unit",
                    timeText = formatDuration((p.timeMillis - startMillis) / 1000),
                    anchorX = x,
                    bg = tooltipBg,
                    fg = tooltipText,
                    plot = Rect(plotLeft, plotTop, plotRight, plotBottom),
                )
            }
        }

        if (scale > 1f) {
            Text(
                "Reset",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            scale = 1f
                            pan = 0f
                            selected = null
                        }
                    },
            )
        }
    }
}

private fun axisLabelStyle(color: Color) = TextStyle(color = color, fontSize = 10.sp)

private fun DrawScope.drawTooltip(
    measurer: TextMeasurer,
    valueText: String,
    timeText: String,
    anchorX: Float,
    bg: Color,
    fg: Color,
    plot: Rect,
) {
    val padH = 10.dp.toPx()
    val padV = 6.dp.toPx()
    val gap = 1.dp.toPx()
    val value = measurer.measure(
        valueText,
        TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    )
    val time = measurer.measure(timeText, TextStyle(color = fg.copy(alpha = 0.7f), fontSize = 10.sp))
    val contentW = maxOf(value.size.width, time.size.width).toFloat()
    val boxW = contentW + padH * 2
    val boxH = value.size.height + time.size.height + padV * 2 + gap
    val left = (anchorX - boxW / 2f).coerceIn(plot.left, plot.right - boxW)
    val top = plot.top
    drawRoundRect(
        color = bg,
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    // Centre both lines within the box.
    drawText(value, topLeft = Offset(left + (boxW - value.size.width) / 2f, top + padV))
    drawText(
        time,
        topLeft = Offset(left + (boxW - time.size.width) / 2f, top + padV + value.size.height + gap),
    )
}
