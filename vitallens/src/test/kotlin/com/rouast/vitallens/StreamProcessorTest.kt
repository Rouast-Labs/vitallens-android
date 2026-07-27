package com.rouast.vitallens

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceOutcome
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.ROICalculator
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.Waveform
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.roi.ROIStrategy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Real (not virtual) delays throughout, mirroring Swift's own StreamProcessorTests.swift, which
 * uses real Task.sleep durations — see FaceROIStrategyTest's note on why.
 *
 * Every processor here is built with an explicit stub/custom [FrameTransformer], never the
 * class's own default one: the default transformer calls the real ImageProcessor.process() on a
 * real Bitmap, and android.graphics.Bitmap is a "Stub!"-throwing placeholder under a plain JVM
 * unit test (no Robolectric here). The default transformer's actual pixel behavior is already
 * covered separately by ImageProcessorTest/ImageProcessorInstrumentedTest; these tests are only
 * about StreamProcessor's own throttling/state-machine/backoff logic.
 */
class StreamProcessorTest {

    private class FakeCamera : CameraStreaming {
        override val stream: Flow<InputFrame> = flow { awaitCancellation() }
        override suspend fun start() {}
        override fun stop() {}
        override fun showPreview(view: PreviewView) {}
    }

    private class FakeROIStrategy : ROIStrategy {
        @Volatile var currentRoi: Rect? = null

        override suspend fun determineROI(
            bitmap: Bitmap,
            orientation: ImageOrientation,
            isMirrored: Boolean,
            roiMethod: String,
        ): Rect? = currentRoi
    }

    private data class FakeState(val id: String) : InferenceState

    private class FakeInferenceStrategy : InferenceStrategy {
        var inferCallCount = 0
            private set
        val stateHistory = mutableListOf<InferenceState?>()

        @Volatile var shouldFail = false

        override suspend fun bufferConfig(): BufferConfig =
            BufferConfig(minNoState = 4u, minWithState = 2u, streamMax = 10u, fileMax = 10u, overlap = 1u)

        override suspend fun resolveConfig(): ModelConfig = ModelConfig(
            nInputs = 2,
            inputSize = 40,
            fpsTarget = 30.0,
            roiMethod = "face",
            supportedVitals = listOf("heart_rate"),
        )

        fun clearHistory() {
            stateHistory.clear()
        }

        override suspend fun infer(
            window: List<Pair<InferenceUnit, InferenceContext>>,
            state: InferenceState?,
            mode: InferenceMode,
            model: String?,
        ): InferenceOutcome {
            stateHistory.add(state)
            inferCallCount++

            if (shouldFail) {
                throw VitalLensException.ServerError(500, "Mock Failure")
            }

            val result = VitalLensResult(
                face = FaceData(coordinates = null, confidence = null, note = null),
                vitals = emptyMap(),
                waveforms = mapOf(
                    "ppg_waveform" to Waveform(
                        data = listOf(0.1f, 0.2f, 0.3f, 0.4f),
                        confidence = listOf(1f, 1f, 1f, 1f),
                        unit = "unitless",
                        note = null,
                    ),
                ),
                time = listOf(System.currentTimeMillis() / 1000.0),
                fps = 30.0,
                modelUsed = "mock",
                state = null,
                message = null,
                sampleCount = 1,
            )

            return InferenceOutcome(result, FakeState("state_$inferCallCount"))
        }
    }

    private val stubTransformer: FrameTransformer = { _, _, _, _, _ -> InferenceUnit.RgbData(ByteArray(0)) }

    private val dummyBitmap = mock<Bitmap>()

    private lateinit var strategy: FakeInferenceStrategy
    private lateinit var roiStrategy: FakeROIStrategy
    private lateinit var processor: StreamProcessor

    /**
     * ROICalculator.calculateROI's first call pays one-off JNA native-lib load/JIT warmup cost
     * that can itself exceed the small millisecond-scale delays these tests use — see
     * FaceROIStrategyTest's identical warmup for the full explanation.
     */
    @Before
    fun setUp() {
        ROICalculator.calculateROI(Rect(0f, 0f, 1f, 1f), "face")

        strategy = FakeInferenceStrategy()
        roiStrategy = FakeROIStrategy()
        processor = StreamProcessor(
            strategy = strategy,
            camera = FakeCamera(),
            roiStrategy = roiStrategy,
            transformer = stubTransformer,
        )
        runBlocking { processor.start() }
    }

    @After
    fun tearDown() {
        processor.stop()
    }

    private fun makeFrame(time: Double) = InputFrame(dummyBitmap, ImageOrientation.UP, isMirrored = true, timestamp = time)

    @Test
    fun `happy path calls the inference strategy once the buffer fills`() = runBlocking {
        roiStrategy.currentRoi = Rect(0.25f, 0.25f, 0.5f, 0.5f)

        for (i in 0 until 10) {
            processor.processFrame(makeFrame(i * 0.033))
        }

        delay(200)

        assertTrue("Strategy should be called when buffer fills", strategy.inferCallCount > 0)
    }

    @Test
    fun `no roi means the strategy is never called`() = runBlocking {
        roiStrategy.currentRoi = null

        for (i in 0 until 10) {
            processor.processFrame(makeFrame(i * 0.033))
        }

        delay(200)

        assertEquals("Strategy should NOT be called if no ROIs detected", 0, strategy.inferCallCount)
    }

    @Test
    fun `backs off on repeated failures and recovers once they stop`() = runBlocking {
        roiStrategy.currentRoi = Rect(0.2f, 0.2f, 0.5f, 0.5f)

        for (i in 0 until 5) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(100)
        val initialCount = strategy.inferCallCount
        assertTrue(initialCount > 0)

        strategy.shouldFail = true
        for (i in 10 until 20) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(300)

        strategy.shouldFail = false
        for (i in 20 until 30) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(300)

        assertTrue("Should recover after failure", strategy.inferCallCount > initialCount + 1)
    }

    @Test
    fun `resets internal state after max consecutive retries`() = runBlocking {
        roiStrategy.currentRoi = Rect(0.2f, 0.2f, 0.5f, 0.5f)

        for (i in 0 until 15) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(1000)
        assertTrue("Should have established state over multiple inferences", strategy.stateHistory.size > 1)

        strategy.shouldFail = true
        for (i in 15 until 65) {
            processor.processFrame(makeFrame(i * 0.033))
            delay(25)
        }
        delay(1000)

        strategy.shouldFail = false
        strategy.clearHistory()

        for (i in 100 until 115) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(1000)

        val history = strategy.stateHistory
        assertTrue("Should have performed at least one successful inference after recovery", history.isNotEmpty())
        if (history.isNotEmpty()) {
            assertNull("The FIRST inference after max retries MUST have a nil state due to the internal reset.", history[0])
        }
    }

    @Test
    fun `pause blocks frame flow and resume restores it`() = runBlocking {
        roiStrategy.currentRoi = Rect(0.25f, 0.25f, 0.5f, 0.5f)

        processor.pause()

        for (i in 0 until 10) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(100)
        assertEquals("Strategy should NOT be called while paused", 0, strategy.inferCallCount)

        processor.resume()

        for (i in 10 until 20) {
            processor.processFrame(makeFrame(i * 0.033))
        }
        delay(200)

        assertTrue("Strategy SHOULD be called after resume", strategy.inferCallCount > 0)
    }

    @Test
    fun `a failing transformer does not crash the inference loop`() = runBlocking {
        val failingTransformer: FrameTransformer = { _, _, _, _, _ ->
            throw VitalLensException.ProcessingError("Simulated Transform Fail")
        }
        val failProcessor = StreamProcessor(
            strategy = strategy,
            camera = FakeCamera(),
            roiStrategy = roiStrategy,
            transformer = failingTransformer,
        )
        failProcessor.start()

        roiStrategy.currentRoi = Rect(0.25f, 0.25f, 0.5f, 0.5f)

        for (i in 0 until 10) {
            failProcessor.processFrame(makeFrame(i * 0.033))
        }
        delay(100)

        assertEquals("Inference should not run if transformation fails", 0, strategy.inferCallCount)
        failProcessor.stop()
    }

    @Test
    fun `rapid start-stop cycles do not deadlock`() = runBlocking {
        processor.stop()
        processor.start()
        processor.stop()
        processor.start()
        processor.stop()
    }

    @Test
    fun `inference state carries over between successive inferences`() = runBlocking {
        roiStrategy.currentRoi = Rect(0.2f, 0.2f, 0.1f, 0.1f)

        for (i in 0 until 15) {
            processor.processFrame(makeFrame(i * 0.033))
            if (i == 5) delay(100)
        }
        delay(300)

        val history = strategy.stateHistory
        assertTrue(history.size >= 2)

        if (history.size >= 2) {
            assertNull("The very first inference state must be nil", history[0])
            assertNotNull("The second inference should have received the state from the first", history[1])
            val secondBatchState = history[1] as? FakeState
            assertEquals("state_1", secondBatchState?.id)
        }
    }

    @Test
    fun `stale buffers are pruned before inference when the face is lost`() = runBlocking {
        processor.start()
        strategy.clearHistory()

        roiStrategy.currentRoi = Rect(0.1f, 0.1f, 0.1f, 0.1f)
        processor.processFrame(makeFrame(1.0))

        roiStrategy.currentRoi = null
        processor.processFrame(makeFrame(7.0))

        delay(300)

        assertEquals("Stale buffers must be pruned before inference can be triggered", 0, strategy.inferCallCount)
    }

    @Test
    fun `face state callback fires only on transitions and buffer resets on loss`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        processor.setFaceStateCallback { isPresent -> changes.add(isPresent) }

        roiStrategy.currentRoi = Rect(0.2f, 0.2f, 0.1f, 0.1f)
        processor.processFrame(makeFrame(1.0))
        delay(10)
        assertEquals(1, changes.size)
        assertTrue("Callback should fire with true when face appears", changes.last())

        processor.processFrame(makeFrame(1.1))
        processor.processFrame(makeFrame(1.2))
        delay(10)
        assertEquals("Callback should NOT fire again if state hasn't changed", 1, changes.size)

        roiStrategy.currentRoi = null
        processor.processFrame(makeFrame(1.3))
        delay(10)
        assertEquals(2, changes.size)
        assertFalse("Callback should fire with false when face is lost", changes.last())

        delay(100)
        assertEquals(
            "API calls should not have been made because the buffer was purged on face loss",
            0,
            strategy.inferCallCount,
        )
    }

    @Test
    fun `uses the custom transformer when one is provided`() = runBlocking {
        var wasCalled = false
        val customTransformer: FrameTransformer = { _, _, _, _, _ ->
            wasCalled = true
            InferenceUnit.RgbData(byteArrayOf(0xFF.toByte(), 0x00, 0x00))
        }

        val customRoiStrategy = FakeROIStrategy().apply { currentRoi = Rect(0f, 0f, 1f, 1f) }
        val customProcessor = StreamProcessor(
            strategy = FakeInferenceStrategy(),
            camera = FakeCamera(),
            roiStrategy = customRoiStrategy,
            transformer = customTransformer,
        )
        customProcessor.start()

        customProcessor.processFrame(makeFrame(1.0))
        delay(50)

        assertTrue("Custom transformer should have been called", wasCalled)
        customProcessor.stop()
    }
}
