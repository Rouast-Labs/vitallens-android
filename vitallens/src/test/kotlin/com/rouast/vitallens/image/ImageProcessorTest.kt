package com.rouast.vitallens.image

import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageProcessorTest {

    @Test
    fun `computes the pixel crop rect for a normalized ROI`() {
        val crop = computeCropRect(bitmapWidth = 100, bitmapHeight = 100, roi = Rect(0.5f, 0.0f, 0.5f, 0.5f))
        assertEquals(50, crop.x)
        assertEquals(0, crop.y)
        assertEquals(50, crop.width)
        assertEquals(50, crop.height)
    }

    @Test
    fun `a full-frame ROI covers the whole bitmap`() {
        val crop = computeCropRect(bitmapWidth = 64, bitmapHeight = 64, roi = Rect(0f, 0f, 1f, 1f))
        assertEquals(0, crop.x)
        assertEquals(0, crop.y)
        assertEquals(64, crop.width)
        assertEquals(64, crop.height)
    }

    @Test
    fun `throws for a ROI extending past the right edge`() {
        val exception = try {
            computeCropRect(bitmapWidth = 100, bitmapHeight = 100, roi = Rect(1.1f, 0f, 0.5f, 0.5f))
            null
        } catch (e: VitalLensException.ProcessingError) {
            e
        }
        assertEquals("ROI out of bounds", exception?.detail)
    }

    @Test
    fun `throws for a ROI with a negative origin`() {
        val exception = try {
            computeCropRect(bitmapWidth = 100, bitmapHeight = 100, roi = Rect(-0.1f, 0f, 0.5f, 0.5f))
            null
        } catch (e: VitalLensException.ProcessingError) {
            e
        }
        assertEquals("ROI out of bounds", exception?.detail)
    }
}
