package com.rouast.vitallens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceOutcome
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.Vital
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ModelConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Needs a real Android runtime (Context + MediaMetadataRetriever, both via FileSource), so this
 * lives under androidTest rather than test — same placement rationale as
 * FileSourceInstrumentedTest.
 *
 * Uses a small synthetic solid-white 128x128/30fps/30-frame video rather than the real
 * sample_video_2.mp4 dataset asset: the detector/strategy here are fakes that never look at
 * actual pixel content, and FileSource's per-frame seek-and-decode is too slow to run over the
 * full ~630-frame real video in a test.
 */
@RunWith(AndroidJUnit4::class)
class FileProcessorInstrumentedTest {

    private class FakeFaceDetector(private val rect: Rect?) : FaceDetecting {
        override suspend fun detectFace(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean): Rect? = rect
    }

    private class FakeInferenceStrategy : InferenceStrategy {
        private val mutex = Mutex()
        private var resolveConfigCalledFlag = false
        private var inferCallCountValue = 0
        private var currentTime = 0.0

        suspend fun resolveConfigCalled(): Boolean = mutex.withLock { resolveConfigCalledFlag }
        suspend fun inferCallCount(): Int = mutex.withLock { inferCallCountValue }

        override suspend fun bufferConfig(): BufferConfig =
            BufferConfig(minNoState = 4u, minWithState = 2u, streamMax = 10u, fileMax = 10u, overlap = 1u)

        override suspend fun resolveConfig(): ModelConfig {
            mutex.withLock { resolveConfigCalledFlag = true }
            return ModelConfig(
                nInputs = 2,
                inputSize = 40,
                fpsTarget = 30.0,
                roiMethod = "face",
                supportedVitals = listOf("heart_rate"),
            )
        }

        override suspend fun infer(
            window: List<Pair<InferenceUnit, InferenceContext>>,
            state: InferenceState?,
            mode: InferenceMode,
            model: String?,
        ): InferenceOutcome {
            val count = window.size
            val startT = mutex.withLock {
                inferCallCountValue++
                val t = currentTime
                currentTime += count / 30.0
                t
            }

            val times = (0 until count).map { startT + it / 30.0 }

            val result = VitalLensResult(
                face = FaceData(coordinates = null, confidence = null, note = null),
                vitals = mapOf("heart_rate" to Vital(value = 72.0, confidence = 1.0, unit = "bpm")),
                waveforms = emptyMap(),
                time = times,
                fps = 30.0,
                modelUsed = "mock",
                state = null,
                message = null,
                sampleCount = count,
            )
            return InferenceOutcome(result, state)
        }
    }

    private lateinit var context: Context
    private lateinit var videoUri: Uri

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        videoUri = copyAssetToCache(context, "solid_white_128.mp4")
    }

    @Test
    fun successfulPipelineDetectsRoiAndAggregatesResults() = runBlocking {
        val detector = FakeFaceDetector(Rect(0.4f, 0.4f, 0.2f, 0.2f))
        val strategy = FakeInferenceStrategy()
        val processor = FileProcessor(context, videoUri, detector)

        val result = processor.process(strategy)

        assertTrue("Should have resolved config", strategy.resolveConfigCalled())
        assertTrue("Inference should have been called", strategy.inferCallCount() > 0)
        assertEquals(30.0, result.fps ?: 0.0, 1.0)

        val samples = result.sampleCount ?: 0
        assertTrue("Should have produced multiple stitched samples", samples > 5)
        assertEquals("Time array should match sample count", samples, result.time.size)

        val lastTime = result.time.lastOrNull()
        if (lastTime != null) {
            assertTrue("Result duration should be roughly the video length", lastTime > 0.5)
        }
    }

    @Test
    fun noFaceDetectedThrowsProcessingError() = runBlocking {
        val detector = FakeFaceDetector(null)
        val strategy = FakeInferenceStrategy()
        val processor = FileProcessor(context, videoUri, detector)

        val exception = try {
            processor.process(strategy)
            null
        } catch (e: VitalLensException.ProcessingError) {
            e
        }

        assertNotNull("Should have thrown a ProcessingError", exception)
        assertTrue(exception!!.detail.contains("No face detected"))
    }

    @Test
    fun appliesExplicitGlobalRoiWithoutScanning() = runBlocking {
        val explicitRoi = Rect(0.1f, 0.1f, 0.5f, 0.5f)
        val spyDetector = FakeFaceDetector(null)
        val strategy = FakeInferenceStrategy()
        val processor = FileProcessor(context, videoUri, spyDetector)

        val result = processor.process(strategy, globalROI = explicitRoi)

        assertTrue(strategy.inferCallCount() > 0)
        assertTrue((result.sampleCount ?: 0) > 0)
    }

    private fun copyAssetToCache(context: Context, assetName: String): Uri {
        val outFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }
}
