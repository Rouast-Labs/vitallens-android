package com.rouast.vitallens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One cubic Bezier segment of a Catmull-Rom spline, in the coordinate space it was built in. */
internal data class CubicSegment(
    val start: Offset,
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
)

/**
 * Maps [samples] onto `width`x`height` pixel coordinates: x spans evenly left-to-right, y is
 * scaled to the data's own min/max, not zero. Small variations in otherwise-large-valued signals
 * (e.g. a PPG waveform centered far from zero) stay visible instead of being flattened by an
 * unnecessary zero-based scale.
 */
internal fun computeWaveformPoints(samples: List<Double>, width: Float, height: Float): List<Offset> {
    if (samples.isEmpty()) return emptyList()

    val minValue = samples.min()
    val maxValue = samples.max()
    val range = (maxValue - minValue).let { if (it == 0.0) 1.0 else it }
    val lastIndex = (samples.size - 1).coerceAtLeast(1)

    return samples.mapIndexed { index, value ->
        val x = if (samples.size == 1) 0f else (index.toFloat() / lastIndex) * width
        val normalizedY = ((value - minValue) / range).toFloat()
        Offset(x = x, y = height - (normalizedY * height))
    }
}

/**
 * Converts [points] into cubic Bezier segments approximating a uniform (tension 0) Catmull-Rom
 * spline through all of them — Compose's [Path] has no native spline support, only line/cubic/quad
 * segments. Standard conversion: for a segment interpolating P1 to P2, `control1 = P1 + (P2-P0)/6`,
 * `control2 = P2 - (P3-P1)/6`, using the curve's own endpoints as P0/P3 where no real neighbor
 * exists.
 */
internal fun catmullRomSegments(points: List<Offset>): List<CubicSegment> {
    if (points.size < 2) return emptyList()

    return (0 until points.size - 1).map { i ->
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.size - 1)]

        CubicSegment(
            start = p1,
            control1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f),
            control2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f),
            end = p2,
        )
    }
}

/**
 * A lightweight waveform chart for time-series physiological data: a single smooth interpolated
 * line, no axes, hand-rolled via [Canvas]/[Path] since Compose has no built-in charting library.
 */
@Composable
fun WaveformChart(
    samples: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = Color.Red,
    lineWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier) {
        val points = computeWaveformPoints(samples, size.width, size.height)
        if (points.size < 2) return@Canvas

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            catmullRomSegments(points).forEach { segment ->
                cubicTo(
                    segment.control1.x, segment.control1.y,
                    segment.control2.x, segment.control2.y,
                    segment.end.x, segment.end.y,
                )
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** A titled card wrapping [WaveformChart] with a standard background and loading state. */
@Composable
fun WaveformContainer(vitalId: String, history: List<Double>, isReady: Boolean, modifier: Modifier = Modifier) {
    val meta = VitalInfoCache.getInfo(vitalId)
    val title = meta?.displayName ?: vitalId
    val chartColor = meta?.color?.let { parseHexColor(it) } ?: Color.Red

    Column(
        modifier = modifier.background(VitalLensColors.Panel, RoundedCornerShape(12.dp)),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isReady && history.isNotEmpty()) {
                WaveformChart(
                    samples = history,
                    color = chartColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            } else {
                CircularProgressIndicator(color = chartColor)
            }
        }
    }
}
