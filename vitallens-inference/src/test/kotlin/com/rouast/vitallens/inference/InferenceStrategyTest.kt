package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class InferenceStrategyTest {

    @Test
    fun `InferenceContext defaults orientation isMirrored and roi`() {
        val context = InferenceContext(timestamp = 1.5)
        assertEquals(1.5, context.timestamp, 0.0001)
        assertEquals(ImageOrientation.UP, context.orientation)
        assertFalse(context.isMirrored)
        assertEquals(Rect.ZERO, context.roi)
    }

    @Test
    fun `InferenceContext accepts explicit orientation isMirrored and roi`() {
        val roi = Rect(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f)
        val context = InferenceContext(
            timestamp = 2.0,
            orientation = ImageOrientation.RIGHT_MIRRORED,
            isMirrored = true,
            roi = roi,
        )
        assertEquals(ImageOrientation.RIGHT_MIRRORED, context.orientation)
        assertEquals(true, context.isMirrored)
        assertEquals(roi, context.roi)
    }

    @Test
    fun `ImageOrientation has all eight EXIF-style cases`() {
        assertEquals(8, ImageOrientation.entries.size)
    }

    @Test
    fun `InferenceUnit RgbData holds the given bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val unit = InferenceUnit.RgbData(bytes)
        assertSame(bytes, unit.data)
    }
}
