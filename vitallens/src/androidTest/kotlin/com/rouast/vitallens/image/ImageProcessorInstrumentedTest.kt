package com.rouast.vitallens.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ports ImageProcessorTests.swift's quadrant-corner-color tests: rather than trust the
 * crop/scale/rotate/mirror Matrix composition by derivation alone, verify it empirically against
 * bitmaps with distinctly colored corners and check where each corner actually lands.
 */
@RunWith(AndroidJUnit4::class)
class ImageProcessorInstrumentedTest {

    private fun createSolidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    /** Quadrants: TL=Red, TR=Green, BL=Blue, BR=White — matches
     * ImageProcessorTests.swift's createQuadrantBGRAPixelBuffer exactly. */
    private fun createQuadrantBitmap(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val half = size / 2f
        val full = size.toFloat()
        canvas.drawRect(0f, 0f, half, half, Paint().apply { color = Color.RED })
        canvas.drawRect(half, 0f, full, half, Paint().apply { color = Color.GREEN })
        canvas.drawRect(0f, half, half, full, Paint().apply { color = Color.BLUE })
        canvas.drawRect(half, half, full, full, Paint().apply { color = Color.WHITE })
        return bitmap
    }

    private fun Byte.unsigned() = toInt() and 0xFF

    @Test
    fun processSolidRedReturnsCorrectRgb() {
        val bitmap = createSolidBitmap(100, 100, Color.RED)
        val targetSize = 10

        val result = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), targetSize, ImageOrientation.UP, isMirrored = false)
        val rgb = ImageProcessor.toRgbBytes(result)

        assertEquals(targetSize * targetSize * 3, rgb.size)
        assertEquals(255, rgb[0].unsigned())
        assertEquals(0, rgb[1].unsigned())
        assertEquals(0, rgb[2].unsigned())
    }

    @Test
    fun processQuadrantRoiCropsCorrectly() {
        val bitmap = createQuadrantBitmap(100)
        val targetSize = 10

        // ROI = top-right quadrant = green
        val result = ImageProcessor.process(bitmap, Rect(0.5f, 0f, 0.5f, 0.5f), targetSize, ImageOrientation.UP, isMirrored = false)
        val rgb = ImageProcessor.toRgbBytes(result)

        val midIndex = (targetSize * targetSize / 2) * 3
        assertEquals(0, rgb[midIndex].unsigned())
        assertEquals(255, rgb[midIndex + 1].unsigned())
        assertEquals(0, rgb[midIndex + 2].unsigned())
    }

    @Test
    fun processOrientationRightCorrectsToUpright() {
        val bitmap = createQuadrantBitmap(100)
        val targetSize = 10

        val result = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), targetSize, ImageOrientation.RIGHT, isMirrored = false)
        val rgb = ImageProcessor.toRgbBytes(result)

        // output top-left should be raw bottom-left (blue)
        assertEquals(0, rgb[0].unsigned())
        assertEquals(0, rgb[1].unsigned())
        assertEquals(255, rgb[2].unsigned())

        // output bottom-left should be raw bottom-right (white)
        val blIndex = ((targetSize - 1) * targetSize) * 3
        assertEquals(255, rgb[blIndex].unsigned())
        assertEquals(255, rgb[blIndex + 1].unsigned())
        assertEquals(255, rgb[blIndex + 2].unsigned())
    }

    @Test
    fun processMirroredCorrectsToUnmirrored() {
        val bitmap = createQuadrantBitmap(100)
        val targetSize = 10

        val result = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), targetSize, ImageOrientation.UP, isMirrored = true)
        val rgb = ImageProcessor.toRgbBytes(result)

        // output top-left should be raw top-right (green)
        assertEquals(0, rgb[0].unsigned())
        assertEquals(255, rgb[1].unsigned())
        assertEquals(0, rgb[2].unsigned())

        // output top-right should be raw top-left (red)
        val trIndex = (targetSize - 1) * 3
        assertEquals(255, rgb[trIndex].unsigned())
        assertEquals(0, rgb[trIndex + 1].unsigned())
        assertEquals(0, rgb[trIndex + 2].unsigned())
    }

    @Test
    fun processWithOrientationAndMirroringDoesNotCrash() {
        val bitmap = createQuadrantBitmap(100)
        val targetSize = 20

        val result = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), targetSize, ImageOrientation.LEFT, isMirrored = true)
        val rgb = ImageProcessor.toRgbBytes(result)

        assertEquals(targetSize * targetSize * 3, rgb.size)
    }

    @Test
    fun processHandlesDifferentTargetSizesSequentially() {
        val bitmap = createSolidBitmap(100, 100, Color.RED)

        val small = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), 20, ImageOrientation.UP, isMirrored = false)
        assertEquals(20 * 20 * 3, ImageProcessor.toRgbBytes(small).size)

        val large = ImageProcessor.process(bitmap, Rect(0f, 0f, 1f, 1f), 60, ImageOrientation.UP, isMirrored = false)
        assertEquals(60 * 60 * 3, ImageProcessor.toRgbBytes(large).size)
    }
}
