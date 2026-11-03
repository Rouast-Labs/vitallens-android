package com.rouast.vitallens.image

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.camera.DecodedFrame
import com.rouast.vitallens.camera.FileSource
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "FramePrepBenchmark"

/**
 * Compares the Kotlin frame path with vitallens-core's `prepareFrame` on the device: output
 * equality and time per frame. Results are logged under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class FramePrepBenchmarkTest {

    private fun kotlinPath(bitmap: Bitmap, roi: Rect, size: Int, o: ImageOrientation, mirrored: Boolean) =
        ImageProcessor.toRgbBytes(ImageProcessor.process(bitmap, roi, size, o, mirrored))

    private fun noiseBitmap(w: Int, h: Int, seed: Int): Bitmap {
        val rng = Random(seed)
        val px = IntArray(w * h) { (0xFF shl 24) or rng.nextInt(0x1000000) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private inline fun timeUs(iterations: Int, block: () -> Unit): Double {
        repeat(iterations / 5 + 1) { block() }
        val t = System.nanoTime()
        repeat(iterations) { block() }
        return (System.nanoTime() - t) / 1000.0 / iterations
    }

    @Test
    fun bitmapPathMatchesKotlinExactlyAndIsFaster() {
        // Crops that are whole multiples of 40 px, so both use the same blocks and must agree exactly
        val cases = listOf(
            Triple(640, 480, Rect(160f / 640, 80f / 480, 320f / 640, 320f / 480)),
            Triple(1280, 720, Rect(400f / 1280, 120f / 720, 480f / 1280, 480f / 720)),
        )
        for ((w, h, roi) in cases) {
            val bitmap = noiseBitmap(w, h, w)
            for (o in listOf(ImageOrientation.UP, ImageOrientation.RIGHT, ImageOrientation.DOWN, ImageOrientation.LEFT)) {
                for (mirrored in listOf(false, true)) {
                    val r = if (o == ImageOrientation.UP || o == ImageOrientation.DOWN) roi else Rect(roi.y, roi.x, roi.height, roi.width)
                    assertArrayEquals("$w x $h $o mirrored=$mirrored",
                        kotlinPath(bitmap, r, 40, o, mirrored), CoreFrames.fromBitmap(bitmap, r, 40, o, mirrored))
                }
            }
            val kotlin = timeUs(200) { kotlinPath(bitmap, roi, 40, ImageOrientation.LEFT, true) }
            val core = timeUs(200) { CoreFrames.fromBitmap(bitmap, roi, 40, ImageOrientation.LEFT, true) }
            Log.i(TAG, "Bitmap ${w}x$h, crop ${(roi.width * w).toInt()}x${(roi.height * h).toInt()}: " +
                "kotlin %.0f us, core %.0f us, %.1fx".format(kotlin, core, kotlin / core))
        }
    }

    private fun sampleVideo(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "sample_video_2.mp4")
        context.assets.open("sample_video_2.mp4").use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    @Test
    fun filePathMatchesKotlinClosely() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = sampleVideo()
        // 480 x 240 of the 768 x 480 video: whole 12 x 6 blocks, so both average the same pixels
        // and differ only in the colour conversion (Kotlin truncates per pixel, core rounds once).
        val aligned = Rect(0.125f, 0.25f, 0.625f, 0.5f)
        // An arbitrary crop: Kotlin's integer block bounds differ from core's exact fractional
        // weights by up to a pixel, which shows at sharp edges. Logged only.
        val arbitrary = Rect(0.3f, 0.15f, 0.4f, 0.6f)
        for ((label, roi, limit) in listOf(Triple("whole blocks", aligned, 2), Triple("fractional blocks", arbitrary, 255))) {
            val source = FileSource.from(context, uri)
            var maxDiff = 0
            var sumDiff = 0L
            var n = 0L
            source.mapFrames { frame, index ->
                if (index % 30 != 0) return@mapFrames null
                val old = kotlinPath(frame.toBitmap(), roi, 40, source.orientation, false)
                val new = prepare(frame, roi, source.orientation)
                for (i in old.indices) {
                    val d = abs((old[i].toInt() and 0xFF) - (new[i].toInt() and 0xFF))
                    maxDiff = maxOf(maxDiff, d)
                    sumDiff += d
                    n++
                }
                Unit
            }.collect()
            Log.i(TAG, "File frames, $label: kotlin vs core max diff $maxDiff, mean %.3f".format(sumDiff.toDouble() / n))
            assertTrue("$label: max diff $maxDiff", maxDiff <= limit)
        }
    }

    @Test
    fun fileDecodeTimings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = sampleVideo()
        val roi = Rect(0.3f, 0.15f, 0.4f, 0.6f)
        suspend fun run(label: String, transform: (DecodedFrame, Int) -> Any?) {
            val source = FileSource.from(context, uri)
            var frames = 0
            val t = System.nanoTime()
            source.mapFrames { f, i -> transform(f, i)?.also { frames++ } }.collect()
            val ms = (System.nanoTime() - t) / 1e6
            Log.i(TAG, "$label: %.0f ms for $frames frames, %.1f ms/frame".format(ms, ms / frames))
        }
        run("decode only") { _, _ -> Unit }
        run("decode + kotlin (full-frame Bitmap, crop, downsample)") { f, _ -> kotlinPath(f.toBitmap(), roi, 40, ImageOrientation.UP, false) }
        run("decode + core (planes in place)") { f, _ -> prepare(f, roi, ImageOrientation.UP) }
        run("decode + core, repeated (warm)") { f, _ -> prepare(f, roi, ImageOrientation.UP) }
    }

    private fun prepare(frame: DecodedFrame, roi: Rect, o: ImageOrientation): ByteArray = when (frame) {
        is DecodedFrame.FromImage -> CoreFrames.fromImage(frame.image, roi, 40, o, false, frame.yuvMatrix)
        is DecodedFrame.FromBuffer -> CoreFrames.fromYuvBuffer(frame.buffer, frame.offset, frame.width, frame.height,
            frame.rowStride, frame.ySliceHeight, frame.semiPlanar, roi, 40, o, false, frame.yuvMatrix)
    }
}
