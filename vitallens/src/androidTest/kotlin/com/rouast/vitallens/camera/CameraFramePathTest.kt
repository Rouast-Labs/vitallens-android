package com.rouast.vitallens.camera

import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.rouast.vitallens.FrameImage
import com.rouast.vitallens.image.CoreFrames
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.vision.FaceDetector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

private const val TAG = "CameraFramePath"

/** The YUV camera frame path: in-place preparation and NV21 snapshots for detection. */
@RunWith(AndroidJUnit4::class)
class CameraFramePathTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val roi = Rect(0.3f, 0.2f, 0.4f, 0.5f)

    private fun sampleVideo(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "sample_video_2.mp4")
        context.assets.open("sample_video_2.mp4").use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    @Test
    fun nv21SnapshotMatchesThePlanesAndDetectsTheSameFace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = FileSource.from(context, sampleVideo())
        val detector = FaceDetector()
        var checked = 0
        source.mapFrames { frame, index ->
            if (index % 60 != 0 || frame !is DecodedFrame.FromImage) return@mapFrames null
            val live = frame.toFrameImage()
            val copy = live.copyToNv21()
            // Same input to the core, same bytes out
            for (o in listOf(ImageOrientation.UP, ImageOrientation.LEFT)) {
                assertArrayEquals(CoreFrames.fromYuv(live, roi, 40, o, true), CoreFrames.fromYuv(copy, roi, 40, o, true))
            }
            Triple(copy, frame.toBitmap(), CoreFrames.toBitmap(live))
        }.collect { (copy, kotlinBitmap, coreBitmap) ->
            val fromNv21 = detector.detectFace(copy, source.orientation, false)
            val fromBitmap = detector.detectFace(kotlinBitmap, source.orientation, false)
            assertNotNull(fromNv21)
            assertNotNull(fromBitmap)
            // ML Kit sees slightly different pixels (no RGB round trip): boxes agree to a few percent
            for ((a, b) in listOf(fromNv21!!.x to fromBitmap!!.x, fromNv21.y to fromBitmap.y, fromNv21.width to fromBitmap.width)) {
                assertEquals(b, a, 0.03f)
            }
            // Core's YUV -> RGB vs FileSource's Kotlin conversion (truncating, 10-bit coefficients)
            var maxDiff = 0
            val n = kotlinBitmap.width * kotlinBitmap.height
            val k = IntArray(n).also { kotlinBitmap.getPixels(it, 0, kotlinBitmap.width, 0, 0, kotlinBitmap.width, kotlinBitmap.height) }
            val c = IntArray(n).also { coreBitmap.getPixels(it, 0, coreBitmap.width, 0, 0, coreBitmap.width, coreBitmap.height) }
            for (i in 0 until n) for (shift in intArrayOf(0, 8, 16)) {
                maxDiff = maxOf(maxDiff, abs(((k[i] shr shift) and 0xFF) - ((c[i] shr shift) and 0xFF)))
            }
            assertTrue("toBitmap max diff $maxDiff", maxDiff <= 3)
            checked++
        }
        detector.close()
        Log.i(TAG, "video frames checked: $checked")
        assertTrue(checked >= 5)
    }

    @Test
    fun cameraFramesArePreparedInPlace(): Unit = ActivityScenario.launch(EmptyTestActivity::class.java).use {
        for (size in listOf(android.util.Size(640, 480), android.util.Size(1280, 960))) runCameraFrames(size)
    }

    private fun runCameraFrames(size: android.util.Size) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val camera = CameraSource(context).apply { analysisResolution = size }
        camera.start()
        var frames = 0
        try {
            withTimeout(15_000) {
                camera.stream.take(120).collect { frame ->
                    frame.use {
                        val image = it.image as FrameImage.Yuv420
                        val rgb = CoreFrames.fromYuv(image, roi, 40, it.orientation, it.isMirrored)
                        assertEquals(40 * 40 * 3, rgb.size)
                        if (frames % 15 == 0) {
                            val copy = image.copyToNv21()
                            assertArrayEquals(rgb, CoreFrames.fromYuv(copy, roi, 40, it.orientation, it.isMirrored))
                        }
                        if (frames == 60) assertEquals(image.width, image.toBitmap().width)
                        frames++
                    }
                }
            }
        } finally {
            camera.stop()
        }
        assertEquals(120, frames)
    }
}
