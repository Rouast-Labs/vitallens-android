package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class RectTest {

    @Test
    fun `maxX and maxY are computed from x-y plus width-height`() {
        val rect = Rect(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f)
        assertEquals(0.4f, rect.maxX, 0.0001f)
        assertEquals(0.6f, rect.maxY, 0.0001f)
    }

    @Test
    fun `ZERO has all components at zero`() {
        assertEquals(Rect(0f, 0f, 0f, 0f), Rect.ZERO)
    }

    @Test
    fun `equal components produce equal rects`() {
        assertEquals(Rect(0.1f, 0.2f, 0.3f, 0.4f), Rect(0.1f, 0.2f, 0.3f, 0.4f))
    }
}
