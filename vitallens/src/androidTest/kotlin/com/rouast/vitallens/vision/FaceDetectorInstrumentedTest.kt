package com.rouast.vitallens.vision

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.camera.FileSource
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class FaceDetectorInstrumentedTest {

    // runBlocking: see CameraSourceInstrumentedTest's note on why, not runTest.
    @Test
    fun detectsARealFaceInABundledVideoFrame() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")
        val source = FileSource.from(context, uri)
        val frame = source.frames().first()

        FaceDetector().use { detector ->
            val rect = detector.detectFace(frame, ImageOrientation.UP, isMirrored = false)

            assertNotNull("Should detect a face in the sample video", rect)
            checkNotNull(rect)
            assertTrue("x should be in [0, 1]", rect.x in 0.0f..1.0f)
            assertTrue("y should be in [0, 1]", rect.y in 0.0f..1.0f)
            assertTrue("width should be a plausible face size", rect.width in 0.05f..0.9f)
            assertTrue("height should be a plausible face size", rect.height in 0.05f..0.9f)
        }
    }

    @Test
    fun returnsNullForABlankImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)

        FaceDetector().use { detector ->
            val rect = detector.detectFace(bitmap, ImageOrientation.UP, isMirrored = false)
            assertNull("Should not detect a face in a blank image", rect)
        }
    }

    private fun copyAssetToCache(context: android.content.Context, assetName: String): android.net.Uri {
        val outFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return android.net.Uri.fromFile(outFile)
    }
}
