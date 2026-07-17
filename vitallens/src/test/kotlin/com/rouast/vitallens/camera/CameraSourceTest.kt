package com.rouast.vitallens.camera

import com.rouast.vitallens.inference.ImageOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSourceTest {

    @Test
    fun `0 degrees maps to UP`() {
        assertEquals(ImageOrientation.UP, rotationDegreesToOrientation(0))
    }

    @Test
    fun `90 degrees maps to RIGHT`() {
        assertEquals(ImageOrientation.RIGHT, rotationDegreesToOrientation(90))
    }

    @Test
    fun `180 degrees maps to DOWN`() {
        assertEquals(ImageOrientation.DOWN, rotationDegreesToOrientation(180))
    }

    @Test
    fun `270 degrees maps to LEFT`() {
        assertEquals(ImageOrientation.LEFT, rotationDegreesToOrientation(270))
    }

    @Test
    fun `negative and over-360 degrees normalize correctly`() {
        assertEquals(ImageOrientation.RIGHT, rotationDegreesToOrientation(-270))
        assertEquals(ImageOrientation.LEFT, rotationDegreesToOrientation(630))
    }
}
