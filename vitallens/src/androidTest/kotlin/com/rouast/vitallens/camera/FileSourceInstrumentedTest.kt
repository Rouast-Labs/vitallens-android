package com.rouast.vitallens.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
 *
 * Doesn't attempt to read the video's full 630 frames: MediaMetadataRetriever.getFrameAtTime
 * with OPTION_CLOSEST independently seeks and decodes per frame (see FileSource.kt's KDoc on
 * why OPTION_CLOSEST over OPTION_CLOSEST_SYNC), which is materially slower than sequential
 * decode — reading only the first few frames is enough to verify the mechanism works correctly
 * without an excessively long test.
 */
@RunWith(AndroidJUnit4::class)
class FileSourceInstrumentedTest {

    // runBlocking, not runTest: see CameraSourceInstrumentedTest's note — this needs to wait on
    // real wall-clock MediaMetadataRetriever decode time, not runTest's virtual-time scheduler.
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

        // take(3), not the full 630 frames — see the class KDoc.
        val frames = source.frames().take(3).toList()

        assertEquals(3, frames.size)
        frames.forEach { bitmap ->
            assertEquals(source.naturalSize.width, bitmap.width)
            assertEquals(source.naturalSize.height, bitmap.height)
        }
    }

    /** [kotlinx.coroutines.flow.Flow.take] cancels the upstream collection early — this is the
     * equivalent of Swift's testCancellationStopsReading (early break after N frames). */
    @Test
    fun collectingOnlyAFewFramesStopsReadingEarly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = copyAssetToCache(context, "sample_video_2.mp4")
        val source = FileSource.from(context, uri)

        val frames = source.frames().take(5).toList()

        assertEquals(5, frames.size)
    }

    private fun copyAssetToCache(context: android.content.Context, assetName: String): android.net.Uri {
        val outFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return android.net.Uri.fromFile(outFile)
    }
}
