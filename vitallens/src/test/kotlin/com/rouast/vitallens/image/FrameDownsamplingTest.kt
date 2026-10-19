package com.rouast.vitallens.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
private fun red(p: Int) = (p shr 16) and 0xFF
private fun green(p: Int) = (p shr 8) and 0xFF
private fun blue(p: Int) = p and 0xFF

class FrameDownsamplingTest {

    // boxDownsample

    @Test
    fun `a constant image stays constant`() {
        val pixels = IntArray(30 * 30) { argb(10, 120, 240) }
        val out = boxDownsample(pixels, 30, 30, 4)
        assertEquals(16, out.size)
        assertTrue(out.all { it == argb(10, 120, 240) })
    }

    @Test
    fun `each output pixel is the average of its block`() {
        // 4x4 source into 2x2: every block holds the values 0, 100, 100, 200 in the red channel
        val src = intArrayOf(
            0, 100, 0, 100,
            100, 200, 100, 200,
            0, 100, 0, 100,
            100, 200, 100, 200,
        ).map { argb(it, 0, 0) }.toIntArray()

        val out = boxDownsample(src, 4, 4, 2)

        assertEquals(listOf(100, 100, 100, 100), out.map { red(it) })
    }

    @Test
    fun `colour channels are averaged independently`() {
        val src = intArrayOf(argb(0, 50, 200), argb(100, 150, 0), argb(0, 50, 200), argb(100, 150, 0))
        val out = boxDownsample(src, 2, 2, 1)
        assertEquals(argb(50, 100, 100), out[0])
    }

    @Test
    fun `block boundaries cover the whole source when the ratio is not an integer`() {
        // 5x5 into 2x2: all source pixels contribute, none is skipped
        val src = IntArray(25) { argb(if (it == 24) 250 else 0, 0, 0) }
        val out = boxDownsample(src, 5, 5, 2)
        assertTrue("the bottom right pixel reaches the bottom right output", red(out[3]) > 0)
        assertEquals(0, red(out[0]))
    }

    @Test
    fun `averaging reduces noise the way a box filter should`() {
        // Sensor noise of +-20 around 128 averaged over 8x8 blocks leaves far less variation than
        // picking single pixels (which is what plain bilinear sampling at a large factor amounts to)
        val random = Random(42)
        val src = IntArray(320 * 320) { argb(128 + random.nextInt(-20, 21), 128, 128) }

        val averaged = boxDownsample(src, 320, 320, 40).map { red(it) }
        val spread = averaged.max() - averaged.min()

        assertTrue("spread after averaging was $spread", spread <= 6)
    }

    @Test
    fun `an output is always opaque`() {
        val out = boxDownsample(IntArray(16) { argb(1, 2, 3) }, 4, 4, 2)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `a source smaller than the target is repeated, not left empty`() {
        val out = boxDownsample(intArrayOf(argb(9, 9, 9), argb(9, 9, 9)), 2, 1, 4)
        assertTrue(out.all { green(it) == 9 && blue(it) == 9 })
    }

    // rotateAndMirror (2x2 image: A B / C D)

    private val a = 1
    private val b = 2
    private val c = 3
    private val d = 4
    private val image = intArrayOf(a, b, c, d)

    @Test
    fun `no rotation and no mirror keeps the image`() {
        assertArrayEquals(image, rotateAndMirror(image, 2, 0, false))
    }

    @Test
    fun `rotates clockwise by 90 degrees`() {
        assertArrayEquals(intArrayOf(c, a, d, b), rotateAndMirror(image, 2, 90, false))
    }

    @Test
    fun `rotates by 180 degrees`() {
        assertArrayEquals(intArrayOf(d, c, b, a), rotateAndMirror(image, 2, 180, false))
    }

    @Test
    fun `rotates clockwise by 270 degrees`() {
        assertArrayEquals(intArrayOf(b, d, a, c), rotateAndMirror(image, 2, 270, false))
    }

    @Test
    fun `mirrors horizontally`() {
        assertArrayEquals(intArrayOf(b, a, d, c), rotateAndMirror(image, 2, 0, true))
    }

    @Test
    fun `mirrors after rotating`() {
        // 90 degrees gives C A / D B, mirrored A C / B D
        assertArrayEquals(intArrayOf(a, c, b, d), rotateAndMirror(image, 2, 90, true))
    }

    @Test
    fun `a negative or large angle is normalised`() {
        assertArrayEquals(rotateAndMirror(image, 2, 270, false), rotateAndMirror(image, 2, -90, false))
        assertArrayEquals(rotateAndMirror(image, 2, 90, false), rotateAndMirror(image, 2, 450, false))
    }
}
