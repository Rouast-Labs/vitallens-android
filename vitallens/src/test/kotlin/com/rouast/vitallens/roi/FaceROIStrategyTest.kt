package com.rouast.vitallens.roi

import android.graphics.Bitmap
import com.rouast.vitallens.FaceDetecting
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.ROICalculator
import com.rouast.vitallens.inference.Rect
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Real (not virtual/runTest) delays throughout, matching Swift's own ROIStrategyTests.swift,
 * which uses real Task.sleep nanosecond durations: FaceROIStrategy's throttling check compares
 * against System.currentTimeMillis() (real wall-clock time), which kotlinx-coroutines-test's
 * virtual clock has no effect on — runTest's virtual delay() calls would "complete" without
 * actually advancing real time, breaking the throttling assertions entirely.
 */
class FaceROIStrategyTest {

    private class FakeFaceDetector(private val results: MutableList<Rect?>) : FaceDetecting {
        var callCount = 0
            private set

        override suspend fun detectFace(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean): Rect? {
            callCount++
            return if (results.isEmpty()) null else results.removeAt(0)
        }
    }

    private val dummyBitmap = mock<Bitmap>()

    /**
     * ROICalculator.calculateROI's first call pays one-off JNA native-lib load/JIT warmup
     * cost that can itself exceed the small millisecond-scale delays these tests use to
     * assert throttling behavior. Pay that cost here, outside any timing-sensitive assertion.
     */
    @Before
    fun warmUpNativeCalculator() {
        ROICalculator.calculateROI(Rect(0f, 0f, 1f, 1f), "face")
    }

    @Test
    fun `first call triggers detection but returns null until it completes`() = runBlocking {
        val rawRect = Rect(0.1f, 0.1f, 0.2f, 0.2f)
        val expectedRect = ROICalculator.calculateROI(rawRect, "face")
        val detector = FakeFaceDetector(mutableListOf(rawRect))
        val strategy = FaceROIStrategy(detector = detector, detectionIntervalMs = 500)

        val initialRoi = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertNull(initialRoi)

        delay(100)
        assertEquals(1, detector.callCount)

        val subsequentRoi = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertEquals(expectedRect, subsequentRoi)
    }

    @Test
    fun `throttling prevents rapid re-triggering`() = runBlocking {
        val detector = FakeFaceDetector(mutableListOf(Rect(0f, 0f, 1f, 1f)))
        val strategy = FaceROIStrategy(detector = detector, detectionIntervalMs = 1000)

        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        delay(50)

        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")

        assertEquals("Should not re-trigger detection before interval elapses", 1, detector.callCount)
    }

    @Test
    fun `updates the cached ROI after the interval elapses`() = runBlocking {
        val rect1 = Rect(0.0f, 0.0f, 0.1f, 0.1f)
        val rect2 = Rect(0.5f, 0.5f, 0.1f, 0.1f)
        val detector = FakeFaceDetector(mutableListOf(rect1, rect2))
        val strategy = FaceROIStrategy(detector = detector, detectionIntervalMs = 100)

        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        delay(50)

        val roi1 = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertEquals(ROICalculator.calculateROI(rect1, "face"), roi1)

        delay(150)

        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        delay(50)

        val roi2 = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertEquals(ROICalculator.calculateROI(rect2, "face"), roi2)

        assertEquals(2, detector.callCount)
    }

    @Test
    fun `drops the ROI immediately when detection fails`() = runBlocking {
        val validRect = Rect(0.2f, 0.2f, 0.2f, 0.2f)
        val detector = FakeFaceDetector(mutableListOf(validRect, null, null))
        val strategy = FaceROIStrategy(detector = detector, detectionIntervalMs = 100)

        strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        delay(120)

        val roi1 = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertEquals(ROICalculator.calculateROI(validRect, "face"), roi1)

        delay(120)

        val roi2 = strategy.determineROI(dummyBitmap, ImageOrientation.UP, isMirrored = false, roiMethod = "face")
        assertNull("Should immediately drop the ROI if detection fails", roi2)

        delay(50)
        assertEquals("Should have triggered detection 3 times", 3, detector.callCount)
    }

    @Test
    fun `close does not throw`() = runBlocking {
        val strategy = FaceROIStrategy(detector = FakeFaceDetector(mutableListOf()), detectionIntervalMs = 500)
        strategy.close()
    }
}
