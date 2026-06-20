package me.crsib.freecartracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Minimal line chart drawn on a Canvas — no chart dependency, keeps the APK small.
 * [values] are plotted left-to-right; the vertical axis auto-scales to the data range.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF1565C0),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val pad = size.height * 0.1f
        val usableH = size.height - pad * 2

        fun pointAt(i: Int): Offset {
            val x = stepX * i
            val norm = (values[i] - min) / range
            val y = pad + (1f - norm) * usableH
            return Offset(x, y)
        }

        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f))
        values.indices.forEach { i ->
            val p = pointAt(i)
            drawCircle(lineColor, radius = 5f, center = p)
        }
    }
}
