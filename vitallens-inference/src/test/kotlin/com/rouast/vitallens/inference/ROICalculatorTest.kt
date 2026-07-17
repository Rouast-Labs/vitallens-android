package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ROICalculatorTest {

    @Test
    fun `calculateROI transforms the input rect for each standard method`() {
        val input = Rect(x = 0.2f, y = 0.2f, width = 0.2f, height = 0.2f)
        val methods = listOf("face", "forehead", "upper_body", "upper_body_cropped")

        for (method in methods) {
            val result = ROICalculator.calculateROI(input, method)

            assertTrue(result.x >= 0.0f)
            assertTrue(result.maxX <= 1.0f)
            assertTrue(result.width > 0.0f)
            assertTrue(result.height > 0.0f)
            assertNotEquals("ROI calculation for $method should transform the input rect", input, result)
        }
    }

    @Test
    fun `calculateROI falls back to upper_body_cropped for unknown methods`() {
        val input = Rect(x = 0.2f, y = 0.2f, width = 0.2f, height = 0.2f)

        val expectedFallback = ROICalculator.calculateROI(input, "upper_body_cropped")
        val resultInvalid = ROICalculator.calculateROI(input, "undefined_method_string")

        assertEquals(expectedFallback, resultInvalid)
    }

    @Test
    fun `computeIoU returns 0 for no overlap`() {
        val a = Rect(x = 0.0f, y = 0.0f, width = 0.2f, height = 0.2f)
        val b = Rect(x = 0.5f, y = 0.5f, width = 0.2f, height = 0.2f)
        assertEquals(0.0f, ROICalculator.computeIoU(a, b), 0.0001f)
    }

    @Test
    fun `computeIoU returns 1 for full overlap`() {
        val c = Rect(x = 0.1f, y = 0.1f, width = 0.3f, height = 0.3f)
        assertEquals(1.0f, ROICalculator.computeIoU(c, c), 0.001f)
    }

    @Test
    fun `computeIoU computes partial overlap ratio`() {
        // Area 0.01 / Union 0.03
        val rect1 = Rect(x = 0.0f, y = 0.0f, width = 0.2f, height = 0.1f)
        val rect2 = Rect(x = 0.1f, y = 0.0f, width = 0.2f, height = 0.1f)
        assertEquals(0.333f, ROICalculator.computeIoU(rect1, rect2), 0.001f)
    }
}
