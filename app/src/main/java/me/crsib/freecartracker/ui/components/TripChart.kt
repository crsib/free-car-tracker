package me.crsib.freecartracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.abs

/** One plotted sample: a value at a wall-clock time, so the chart can show "time into trip". */
data class ChartPoint(val timeMillis: Long, val value: Float)

/**
 * Shared zoom / pan / scrub state for a group of [TripChart]s that should move together. The
 * viewport ([scale], [pan]) and the scrub cursor ([selectedTimeMillis]) are expressed against a
 * single absolute-time domain, so every chart shows the same time window and the same instant —
 * even though each chart's series omits a different subset of samples (nulls).
 */
@Stable
class ChartSync {
    /** Time-axis zoom; 1 = whole trip, larger = zoomed in. */
    var scale by mutableFloatStateOf(1f)
    /** Left edge of the visible window as a fraction [0,1] of the time domain. */
    var pan by mutableFloatStateOf(0f)
    /** Absolute time (millis) of the scrub cursor, or null when not scrubbing. */
    var selectedTimeMillis by mutableStateOf<Long?>(null)

    fun reset() {
        scale = 1f
        pan = 0f
        selectedTimeMillis = null
    }
}

@Composable
fun rememberChartSync(vararg keys: Any?): ChartSync = remember(*keys) { ChartSync() }

/**
 * Interactive line chart drawn on a Canvas — no chart dependency, keeps the APK small.
 *
 * Gestures:
 * - **Pinch** to zoom the time axis; **two-finger drag** to pan while zoomed.
 * - **Tap / drag** with one finger to scrub a cursor that reads off the value and the
 *   elapsed time at that point.
 *
 * All of the above mutate the shared [sync], so sibling charts using the same instance move in
 * lockstep. The value range (min/max of this series) is always labelled on the left axis.
 *
 * @param domainStartMillis / @param domainEndMillis the shared time domain (typically the trip's
 *   first/last sample), so cursor and zoom line up across charts with different sample sets.
 */
@Composable
fun TripChart(
    points: List<ChartPoint>,
    sync: ChartSync,
    domainStartMillis: Long,
    domainEndMillis: Long,
    modifier: Modifier = Modifier,
    unit: String = "",
    lineColor: Color = Color(0xFF1565C0),
    valueFormat: (Float) -> String = { formatNumber(it.toDouble()) },
    /** Tooltip's secondary line for a point's absolute time. Defaults to elapsed time from the start. */
    timeLabel: (Long) -> String = { t -> formatDuration((t - domainStartMillis) / 1000) },
    /** Draw a horizontal line at the mean of the values. */
    averageLine: Boolean = false,
    /** Draw a least-squares linear regression line across the series. */
    trendLine: Boolean = false,
    /** Render bars instead of a connected line. Good for a handful of discrete points. */
    barChart: Boolean = false,
) {
    if (points.size < 2) return

    val dataMin = points.minOf { it.value }
    val dataMax = points.maxOf { it.value }
    // Bars must include the zero baseline so their height encodes magnitude honestly.
    val min = if (barChart) minOf(0f, dataMin) else dataMin
    val max = if (barChart) maxOf(0f, dataMax) else dataMax
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val domainSpan = (domainEndMillis - domainStartMillis).coerceAtLeast(1L)

    val maxZoom = 40f
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.primary
    val haloColor = MaterialTheme.colorScheme.surface
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipText = MaterialTheme.colorScheme.inverseOnSurface
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant
    val trendColor = Color(0xFFEF6C00)
    val barBorderColor = Color(0xFF0D3B79) // darker rim so adjacent bars read as distinct
    val measurer = rememberTextMeasurer()

    // Mean and least-squares fit (value = a + b·tRelative), computed over all points. The fit uses
    // time relative to the domain start to keep the numbers small.
    val mean = points.map { it.value }.average().toFloat()
    val fit = remember(points) {
        val xs = points.map { (it.timeMillis - domainStartMillis).toDouble() }
        val ys = points.map { it.value.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in points.indices) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        val slope = if (sxx != 0.0) sxy / sxx else 0.0
        slope to (my - slope * mx) // (b, a)
    }

    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                // Taps: read the value at that x (and consume, so a tap on the chart doesn't bubble
                // to the screen's tap-to-clear handler).
                .pointerInput(points, domainStartMillis, domainEndMillis) {
                    val leftAxis = 44.dp.toPx()
                    val plotW = (size.width - leftAxis).coerceAtLeast(1f)
                    detectTapGestures { offset ->
                        if (offset.x >= leftAxis) {
                            val frac = (sync.pan + ((offset.x - leftAxis) / plotW) / sync.scale).coerceIn(0f, 1f)
                            sync.selectedTimeMillis = domainStartMillis + (frac * domainSpan).toLong()
                        }
                    }
                }
                // Drags: a mostly-horizontal one finger scrubs (consumed); a mostly-vertical one is
                // left for the surrounding scroll container; two fingers zoom/pan.
                .pointerInput(points, domainStartMillis, domainEndMillis) {
                    val leftAxis = 44.dp.toPx()
                    val plotW = (size.width - leftAxis).coerceAtLeast(1f)
                    val slop = viewConfiguration.touchSlop

                    fun timeAt(x: Float): Long {
                        val frac = (sync.pan + ((x - leftAxis) / plotW) / sync.scale).coerceIn(0f, 1f)
                        return domainStartMillis + (frac * domainSpan).toLong()
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        // 0 = undecided, 1 = horizontal scrub, 2 = vertical (defer to scroll), 3 = pinch.
                        var mode = 0

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                mode = 3
                                sync.selectedTimeMillis = null
                                val zoom = event.calculateZoom()
                                val panChange = event.calculatePan().x
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f || panChange != 0f) {
                                    val cx = ((centroid.x - leftAxis) / plotW).coerceIn(0f, 1f)
                                    val fracUnder = sync.pan + cx / sync.scale
                                    val newScale = (sync.scale * zoom).coerceIn(1f, maxZoom)
                                    sync.scale = newScale
                                    sync.pan = (fracUnder - cx / newScale - (panChange / plotW) / newScale)
                                        .coerceIn(0f, 1f - 1f / newScale)
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (pressed.size == 1 && mode != 3) {
                                val c = pressed.first()
                                if (mode == 0) {
                                    val dx = c.position.x - startX
                                    val dy = c.position.y - startY
                                    if (abs(dx) > slop || abs(dy) > slop) {
                                        mode = if (abs(dx) >= abs(dy)) 1 else 2
                                    }
                                }
                                if (mode == 1 && c.position.x >= leftAxis) {
                                    sync.selectedTimeMillis = timeAt(c.position.x)
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

            val scale = sync.scale
            val pan = sync.pan

            fun fracOf(t: Long): Float = (t - domainStartMillis).toFloat() / domainSpan
            fun xForTime(t: Long): Float = plotLeft + ((fracOf(t) - pan) * scale) * plotW
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

            // Data: only the visible time window (plus one point each side for line continuity),
            // so deep zoom stays cheap.
            val visStartT = domainStartMillis + (pan * domainSpan).toLong()
            val visEndT = domainStartMillis + ((pan + 1f / scale) * domainSpan).toLong()
            var firstIdx = points.indexOfFirst { it.timeMillis >= visStartT }
            if (firstIdx < 0) firstIdx = points.lastIndex
            firstIdx = (firstIdx - 1).coerceAtLeast(0)
            var lastIdx = points.indexOfLast { it.timeMillis <= visEndT }
            if (lastIdx < 0) lastIdx = 0
            lastIdx = (lastIdx + 1).coerceAtMost(points.lastIndex)
            if (barChart) {
                val baselineY = yForValue(min)
                val barW = ((plotW * scale) / points.size * 0.55f).coerceIn(4.dp.toPx(), 48.dp.toPx())
                val borderW = 1.5.dp.toPx()
                clipRect(plotLeft, plotTop, plotRight, plotBottom) {
                    for (i in firstIdx..lastIdx) {
                        val cx = xForTime(points[i].timeMillis)
                        val topY = yForValue(points[i].value)
                        val topLeft = Offset(cx - barW / 2f, minOf(topY, baselineY))
                        val sizeRect = Size(barW, abs(baselineY - topY))
                        drawRect(color = lineColor, topLeft = topLeft, size = sizeRect)
                        drawRect(color = barBorderColor, topLeft = topLeft, size = sizeRect, style = Stroke(width = borderW))
                    }
                }
            } else if (lastIdx > firstIdx) {
                val path = Path().apply {
                    moveTo(xForTime(points[firstIdx].timeMillis), yForValue(points[firstIdx].value))
                    for (i in firstIdx + 1..lastIdx) lineTo(xForTime(points[i].timeMillis), yForValue(points[i].value))
                }
                drawPath(path, color = lineColor, style = Stroke(width = 3.dp.toPx()))
            }

            // Reference overlays, on top of the data: mean (dashed) and a linear trend fit.
            if (averageLine) {
                val y = yForValue(mean)
                drawLine(
                    averageColor.copy(alpha = 0.7f),
                    Offset(plotLeft, y), Offset(plotRight, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }
            if (trendLine) {
                val (slope, intercept) = fit
                val t0 = points.first().timeMillis
                val t1 = points.last().timeMillis
                val y0 = (intercept + slope * (t0 - domainStartMillis)).toFloat()
                val y1 = (intercept + slope * (t1 - domainStartMillis)).toFloat()
                drawLine(
                    trendColor,
                    Offset(xForTime(t0), yForValue(y0)), Offset(xForTime(t1), yForValue(y1)),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            // Scrub cursor + tooltip: snap to this series' sample nearest the shared selected time.
            val sel = sync.selectedTimeMillis
            if (sel != null) {
                var nearest = 0
                var bestDist = Long.MAX_VALUE
                for (i in points.indices) {
                    val d = abs(points[i].timeMillis - sel)
                    if (d < bestDist) {
                        bestDist = d
                        nearest = i
                    }
                }
                val p = points[nearest]
                val x = xForTime(p.timeMillis)
                if (x in plotLeft..plotRight) {
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
                        timeText = timeLabel(p.timeMillis),
                        anchorX = x,
                        bg = tooltipBg,
                        fg = tooltipText,
                        plot = Rect(plotLeft, plotTop, plotRight, plotBottom),
                    )
                }
            }
        }

        if (sync.scale > 1f) {
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
                            sync.reset()
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
