package com.rouast.vitallens.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformChartTest {

    @Test
    fun `computeWaveformPoints returns nothing for an empty sample list`() {
        assertEquals(emptyList<Offset>(), computeWaveformPoints(emptyList(), width = 100f, height = 100f))
    }

    @Test
    fun `computeWaveformPoints centers a single sample horizontally at the start`() {
        val points = computeWaveformPoints(listOf(5.0), width = 100f, height = 100f)
        assertEquals(1, points.size)
        assertEquals(0f, points[0].x, 0.001f)
    }

    @Test
    fun `computeWaveformPoints spans the full width evenly`() {
        val points = computeWaveformPoints(listOf(1.0, 2.0, 3.0, 4.0, 5.0), width = 100f, height = 100f)
        assertEquals(0f, points[0].x, 0.001f)
        assertEquals(25f, points[1].x, 0.001f)
        assertEquals(50f, points[2].x, 0.001f)
        assertEquals(75f, points[3].x, 0.001f)
        assertEquals(100f, points[4].x, 0.001f)
    }

    @Test
    fun `computeWaveformPoints scales y to the data's own min-max, not zero`() {
        // A tiny wobble far from zero should still span the full chart height — small variations
        // stay visible instead of being flattened by an unnecessary zero-based scale.
        val points = computeWaveformPoints(listOf(1000.0, 1000.5, 1000.0), width = 100f, height = 100f)
        assertEquals(100f, points[0].y, 0.001f) // min value -> bottom
        assertEquals(0f, points[1].y, 0.001f) // max value -> top
        assertEquals(100f, points[2].y, 0.001f)
    }

    @Test
    fun `computeWaveformPoints does not crash when all samples are identical`() {
        val points = computeWaveformPoints(listOf(3.0, 3.0, 3.0), width = 100f, height = 100f)
        assertEquals(3, points.size)
        points.forEach { assertTrue(it.y.isFinite()) }
    }

    @Test
    fun `catmullRomSegments returns nothing for fewer than 2 points`() {
        assertEquals(emptyList<CubicSegment>(), catmullRomSegments(listOf(Offset(0f, 0f))))
    }

    @Test
    fun `catmullRomSegments returns one segment per adjacent point pair`() {
        val points = listOf(Offset(0f, 0f), Offset(1f, 1f), Offset(2f, 0f), Offset(3f, 1f))
        val segments = catmullRomSegments(points)
        assertEquals(3, segments.size)
        assertEquals(points[0], segments[0].start)
        assertEquals(points[1], segments[0].end)
        assertEquals(points[2], segments[2].start)
        assertEquals(points[3], segments[2].end)
    }

    @Test
    fun `catmullRomSegments clamps neighbor lookups at the ends of the curve`() {
        // Standard Catmull-Rom-to-cubic-Bezier conversion (uniform, tension 0): for a segment
        // interpolating P1 to P2, control1 = P1 + (P2-P0)/6, control2 = P2 - (P3-P1)/6, using the
        // curve's own endpoints as P0/P3 where no real neighbor exists.
        val p0 = Offset(0f, 0f)
        val p1 = Offset(10f, 0f)
        val p2 = Offset(20f, 10f)
        val segments = catmullRomSegments(listOf(p0, p1, p2))

        val firstSegmentExpectedControl1 = Offset(p0.x + (p1.x - p0.x) / 6f, p0.y + (p1.y - p0.y) / 6f)
        assertEquals(firstSegmentExpectedControl1.x, segments[0].control1.x, 0.001f)
        assertEquals(firstSegmentExpectedControl1.y, segments[0].control1.y, 0.001f)

        val secondSegmentExpectedControl2 = Offset(p2.x - (p2.x - p1.x) / 6f, p2.y - (p2.y - p1.y) / 6f)
        assertEquals(secondSegmentExpectedControl2.x, segments[1].control2.x, 0.001f)
        assertEquals(secondSegmentExpectedControl2.y, segments[1].control2.y, 0.001f)
    }
}
