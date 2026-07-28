package com.rouast.vitallens

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.camera.EmptyTestActivity
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceOutcome
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.Waveform
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.roi.ROIStrategy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/**
 * VitalLens's constructor registers a ProcessLifecycleOwner observer, which needs a real
 * Android runtime/main-thread Looper — so this lives under androidTest, not test, mirroring
 * FileProcessorInstrumentedTest's placement rationale.
 */
@RunWith(AndroidJUnit4::class)
class VitalLensInstrumentedTest {

    private class FakeCameraStreaming : CameraStreaming {
        var startCallCount = 0
            private set
        var stopCallCount = 0
            private set

        override val stream: Flow<InputFrame> = flow { awaitCancellation() }

        override suspend fun start() {
            startCallCount++
        }

        override fun stop() {
            stopCallCount++
        }

        override fun showPreview(view: androidx.camera.view.PreviewView) {}
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

        override suspend fun bufferConfig(): BufferConfig =
            BufferConfig(minNoState = 4u, minWithState = 2u, streamMax = 10u, fileMax = 10u, overlap = 1u)

        override suspend fun resolveConfig(): ModelConfig = ModelConfig(
            nInputs = 2,
            inputSize = 40,
            fpsTarget = 30.0,
            roiMethod = "face",
            supportedVitals = listOf("heart_rate"),
        )

        override suspend fun infer(
            window: List<Pair<InferenceUnit, InferenceContext>>,
            state: InferenceState?,
            mode: InferenceMode,
            model: String?,
        ): InferenceOutcome {
            inferCallCount++
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

    private lateinit var context: Context
    private val dummyBitmap = mock<Bitmap>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun makeFrame(time: Double) = InputFrame(dummyBitmap, ImageOrientation.UP, isMirrored = true, timestamp = time)

    @Test
    fun `starting the stream initializes and starts the processor`() = runBlocking {
        val strategy = FakeInferenceStrategy()
        val roiStrategy = FakeROIStrategy()
        val fakeCamera = FakeCameraStreaming()
        val processor = StreamProcessor(strategy = strategy, camera = fakeCamera, roiStrategy = roiStrategy)
        val client = VitalLens(context, processor)

        val stream = client.startStream()

        roiStrategy.currentRoi = Rect(0.25f, 0.25f, 0.5f, 0.5f)
        val baseTime = System.currentTimeMillis() / 1000.0

        launch {
            for (i in 0 until 20) {
                processor.processFrame(makeFrame(baseTime + i * 0.033))
            }
        }

        val received = withTimeout(5_000) { stream.first() }
        assertNotNull("Client should yield results from the processor", received)

        delay(100)
        assertEquals("Camera should have been started", 1, fakeCamera.startCallCount)

        client.stopStream()
        delay(100)

        assertEquals("Camera should have been stopped", 1, fakeCamera.stopCallCount)
    }

    @Test
    fun `backgrounding the app pauses the camera and foregrounding resumes it`() = runBlocking {
        val strategy = FakeInferenceStrategy()
        val roiStrategy = FakeROIStrategy()
        val fakeCamera = FakeCameraStreaming()
        val processor = StreamProcessor(strategy = strategy, camera = fakeCamera, roiStrategy = roiStrategy)
        val client = VitalLens(context, processor)
        delay(50)

        ActivityScenario.launch(EmptyTestActivity::class.java).use { scenario ->
            client.startStream()
            delay(100)
            assertEquals(1, fakeCamera.startCallCount)

            scenario.moveToState(Lifecycle.State.CREATED)
            delay(300)
            assertEquals("Camera should stop on background", 1, fakeCamera.stopCallCount)

            scenario.moveToState(Lifecycle.State.RESUMED)
            delay(300)
            assertEquals("Camera should restart on foreground", 2, fakeCamera.startCallCount)
        }
    }

    @Test
    fun `public properties are set from the constructor`() {
        val client = VitalLens(context, apiKey = "key", method = "vitallens-2.0")
        assertEquals("key", client.apiKey)
        assertEquals("vitallens-2.0", client.method)
    }

    @Test
    fun `face state callback set before starting propagates to the processor`() = runBlocking {
        val strategy = FakeInferenceStrategy()
        val roiStrategy = FakeROIStrategy()
        val fakeCamera = FakeCameraStreaming()
        val processor = StreamProcessor(strategy = strategy, camera = fakeCamera, roiStrategy = roiStrategy)
        val client = VitalLens(context, processor)

        val facePresentReceived = java.util.concurrent.atomic.AtomicBoolean(false)
        client.onFaceStateChanged = { isPresent -> if (isPresent) facePresentReceived.set(true) }

        client.startStream()

        roiStrategy.currentRoi = Rect(0.1f, 0.1f, 0.5f, 0.5f)
        processor.processFrame(makeFrame(1.0))

        withTimeout(2_000) {
            while (!facePresentReceived.get()) delay(10)
        }
        assertTrue(facePresentReceived.get())
    }
}
