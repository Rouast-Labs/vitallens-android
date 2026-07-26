package com.rouast.vitallens.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSourceTest {

    @Test
    fun `uses captureFrameRate when present and valid`() {
        val rate = parseFrameRate(captureFrameRate = "24.0", frameCount = null, durationMs = null)
        assertEquals(24.0f, rate, 0.001f)
    }

    @Test
    fun `falls back to frameCount over duration when captureFrameRate is absent`() {
        val rate = parseFrameRate(captureFrameRate = null, frameCount = "60", durationMs = "2000")
        assertEquals(30.0f, rate, 0.001f)
    }

    @Test
    fun `skips a zero or negative captureFrameRate and falls back`() {
        val rate = parseFrameRate(captureFrameRate = "0", frameCount = "60", durationMs = "2000")
        assertEquals(30.0f, rate, 0.001f)
    }

    @Test
    fun `falls back to the default when nothing is available`() {
        val rate = parseFrameRate(captureFrameRate = null, frameCount = null, durationMs = null)
        assertEquals(30.0f, rate, 0.001f)
    }

    @Test
    fun `falls back to the default when duration is zero`() {
        val rate = parseFrameRate(captureFrameRate = null, frameCount = "60", durationMs = "0")
        assertEquals(30.0f, rate, 0.001f)
    }

    @Test
    fun `falls back to the default when values are unparseable`() {
        val rate = parseFrameRate(captureFrameRate = "not-a-number", frameCount = "also-not", durationMs = "nope")
        assertEquals(30.0f, rate, 0.001f)
    }
}
