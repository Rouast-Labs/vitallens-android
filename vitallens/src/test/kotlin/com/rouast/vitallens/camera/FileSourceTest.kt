package com.rouast.vitallens.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

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

    // A 4x2 frame, row stride 6 (padded), with reddish chroma (Cb 64, Cr 192) in the left half
    // and bluish chroma (Cb 192, Cr 64) in the right half.
    private val width = 4
    private val height = 2
    private val rowStride = 6
    private val luma = ByteBuffer.wrap(ByteArray(rowStride * height) { 128.toByte() })
    private val cb = byteArrayOf(64, 192.toByte())
    private val cr = byteArrayOf(192.toByte(), 64)

    private fun planar(): IntArray = yuvPlanesToArgb(
        y = luma, yRowStride = rowStride, yPixelStride = 1,
        u = ByteBuffer.wrap(cb), v = ByteBuffer.wrap(cr), uvRowStride = 3, uvPixelStride = 1,
        width = width, height = height,
    )

    private fun semiPlanar(): IntArray {
        val interleaved = byteArrayOf(cb[0], cr[0], cb[1], cr[1], 0, 0)
        return yuvPlanesToArgb(
            y = luma, yRowStride = rowStride, yPixelStride = 1,
            u = ByteBuffer.wrap(interleaved),
            v = ByteBuffer.wrap(interleaved, 1, interleaved.size - 1).slice(),
            uvRowStride = rowStride, uvPixelStride = 2,
            width = width, height = height,
        )
    }

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test
    fun `planar and semi-planar chroma give the same pixels`() {
        assertArrayEquals(planar(), semiPlanar())
    }

    @Test
    fun `chroma of each plane reaches the right channel`() {
        val pixels = planar()
        for (row in 0 until height) {
            val left = pixels[row * width]
            val right = pixels[row * width + width - 1]
            assertTrue("left half is red", red(left) > 200 && blue(left) < 50)
            assertTrue("right half is blue", blue(right) > 200 && red(right) < 50)
        }
    }
}
