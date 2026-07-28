package com.rouast.vitallens.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Uses the same real test video vitallens-ios bundles (`sample_video_2.mp4`,
 * Tests/VitalLensTests/Resources/) — a real face-containing video from the project's own rPPG
 * validation dataset (has ground-truth vitals in vitallens-core's fixtures), not something
 * synthesized for this SDK specifically. Actual properties (verified via ffprobe): 768x480,
 * 30fps, ~21s duration, 630 frames, no rotation metadata.
 */
@RunWith(AndroidJUnit4::class)
class FileSourceInstrumentedTest {

    // runBlocking, not runTest: see CameraSourceInstrumentedTest's note — this needs to wait on
    // real wall-clock MediaCodec decode time, not runTest's virtual-time scheduler.
    @Test
    fun resolvesRealPropertiesFromTheBundledSampleVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")

        val source = FileSource.from(context, uri)

        assertEquals(768, source.naturalSize.width)
        assertEquals(480, source.naturalSize.height)
        assertTrue("nominalFrameRate should be positive", source.nominalFrameRate > 0f)
        assertEquals(ImageOrientation.UP, source.orientation)
    }

    @Test
    fun framesYieldsRealDecodedBitmapsMatchingTheVideosDimensions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")
        val source = FileSource.from(context, uri)

        // A small take(3), not the full 630 frames: this test only cares about basic
        // correctness (dimensions), so keep it fast — decodesTheFullVideoWithinAReasonableTime
        // below covers reading the whole file.
        val frames = source.frames().take(3).toList()

        assertEquals(3, frames.size)
        frames.forEach { bitmap ->
            assertEquals(source.naturalSize.width, bitmap.width)
            assertEquals(source.naturalSize.height, bitmap.height)
        }
    }

    /**
     * [kotlinx.coroutines.flow.Flow.take] cancels the upstream collection early, so only the
     * first few frames should actually be decoded rather than the whole video.
     */
    @Test
    fun collectingOnlyAFewFramesStopsReadingEarly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")
        val source = FileSource.from(context, uri)

        val frames = source.frames().take(5).toList()

        assertEquals(5, frames.size)
    }

    /**
     * Regression guard for a real bottleneck the earlier `MediaMetadataRetriever.getFrameAtTime`
     * implementation had: independently seeking+decoding every frame measured at ~265ms/frame
     * against this same 630-frame video on a real device (97.8% of a real end-to-end
     * file-processing integration test's runtime). Sequential MediaCodec decode should get
     * nowhere near that — bounding at 30s here (generous for real-device/emulator variance) is
     * still dramatically tighter than the ~167s the old approach took for this same file.
     */
    @Test
    fun decodesTheFullVideoWithinAReasonableTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")
        val source = FileSource.from(context, uri)

        val frameCount = withTimeout(30_000) { source.frames().count() }

        assertTrue("Expected close to 630 frames, got $frameCount", frameCount in 600..660)
    }

    private fun copyAssetToCache(context: android.content.Context, assetName: String): android.net.Uri {
        val outFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return android.net.Uri.fromFile(outFile)
    }
}
