package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackStateResolverTest {

    @Test
    fun `isFaceGood is true for a centered, large-enough box`() {
        assertTrue(isFaceGood(Rect(x = 0.4f, y = 0.4f, width = 0.2f, height = 0.2f)))
    }

    @Test
    fun `isFaceGood is false when the box is off-center horizontally`() {
        assertFalse(isFaceGood(Rect(x = 0.0f, y = 0.4f, width = 0.2f, height = 0.2f)))
    }

    @Test
    fun `isFaceGood is false when the box is off-center vertically`() {
        assertFalse(isFaceGood(Rect(x = 0.4f, y = 0.0f, width = 0.2f, height = 0.2f)))
    }

    @Test
    fun `isFaceGood is false when the box is too small`() {
        assertFalse(isFaceGood(Rect(x = 0.45f, y = 0.45f, width = 0.1f, height = 0.1f)))
    }

    @Test
    fun `isFaceGood is false when there is no box`() {
        assertFalse(isFaceGood(null))
    }

    @Test
    fun `rollingAverage of an empty history is zero`() {
        assertEquals(0.0, rollingAverage(emptyList(), windowSize = 10), 0.0001)
    }

    @Test
    fun `rollingAverage averages only the most recent samples in the window`() {
        val history = listOf(0.0, 0.0, 1.0, 1.0)
        assertEquals(1.0, rollingAverage(history, windowSize = 2), 0.0001)
    }

    @Test
    fun `rollingAverage uses the whole history when it is shorter than the window`() {
        val history = listOf(0.5, 1.0)
        assertEquals(0.75, rollingAverage(history, windowSize = 10), 0.0001)
    }

    @Test
    fun `isLowSignal is true when face confidence is below threshold`() {
        assertTrue(isLowSignal(avgPpgConf = 0.9, avgFaceConf = 0.4))
    }

    @Test
    fun `isLowSignal is true when ppg confidence is below threshold`() {
        assertTrue(isLowSignal(avgPpgConf = 0.4, avgFaceConf = 0.9))
    }

    @Test
    fun `isLowSignal is false when both confidences meet the threshold`() {
        assertFalse(isLowSignal(avgPpgConf = 0.9, avgFaceConf = 0.9))
    }
}
