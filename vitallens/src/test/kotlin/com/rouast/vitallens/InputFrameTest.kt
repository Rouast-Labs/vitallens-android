package com.rouast.vitallens

import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class InputFrameTest {

    private val uprightRoi = Rect(x = 0.5f, y = 0.0f, width = 0.5f, height = 0.5f)

    @Test
    fun `up unmirrored is unchanged`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.UP, isMirrored = false)
        assertEquals(Rect(0.5f, 0.0f, 0.5f, 0.5f), raw)
    }

    @Test
    fun `up mirrored flips horizontally`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.UP, isMirrored = true)
        assertEquals(Rect(0.0f, 0.0f, 0.5f, 0.5f), raw)
    }

    @Test
    fun `left unmirrored rotates`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.LEFT, isMirrored = false)
        assertEquals(Rect(0.5f, 0.5f, 0.5f, 0.5f), raw)
    }

    @Test
    fun `right unmirrored rotates`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.RIGHT, isMirrored = false)
        assertEquals(Rect(0.0f, 0.0f, 0.5f, 0.5f), raw)
    }

    @Test
    fun `down unmirrored rotates 180`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.DOWN, isMirrored = false)
        assertEquals(Rect(0.0f, 0.5f, 0.5f, 0.5f), raw)
    }

    @Test
    fun `right mirrored combines mirror and rotation`() {
        val raw = uprightRoi.mappedToRaw(orientation = ImageOrientation.RIGHT_MIRRORED, isMirrored = true)
        assertEquals(Rect(0.0f, 0.5f, 0.5f, 0.5f), raw)
    }
}
