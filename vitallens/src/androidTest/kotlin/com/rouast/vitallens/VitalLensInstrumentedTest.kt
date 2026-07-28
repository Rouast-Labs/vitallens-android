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
    // A real (not mocked) Bitmap: Mockito's default (Byte Buddy inline) mock maker doesn't work
    // on a real Android/Dalvik runtime — that's a plain-JVM-unit-test-only trick. A real Android
    // runtime makes a real Bitmap cheap to construct instead, sidestepping the need to mock it.
    //
    // 100x100, matching Swift's own createDummyBuffer(): these tests (unlike StreamProcessorTest,
    // which always supplies an explicit stub transformer) exercise StreamProcessor's *default*
    // transformer, which crops the configured ROI out of this bitmap for real via ImageProcessor
    // — a too-small bitmap makes that crop fail, which processFrame's catch-all silently swallows
    // (matching Swift), so frames would never actually make it into the buffer.
    private val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun makeFrame(time: Double) = InputFrame(dummyBitmap, ImageOrientation.UP, isMirrored = true, timestamp = time)

    // Named without backticks/spaces: a lambda inside a backtick-named test method (e.g. the
    // launch{} below) inherits the enclosing method's name into its generated class name, and
    // DEX (unlike plain JVM class files) rejects spaces there — matches this file's Android
    // instrumented-test siblings (CameraSource/FileSource/FaceDetector/FileProcessor), which
    // already use camelCase for the same reason.
    @Test
    fun startingStreamInitializesAndStartsProcessor() = runBlocking {
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

        // Generous timeout, matching CameraSourceInstrumentedTest's own real-hardware-warmup
        // budget: this is the first test in the class to touch the native Rust core (Session/
        // BufferPlanner construction inside StreamProcessor.start()), and first-touch JNA/native
        // lib loading through a real emulator's Dalvik/ART JNI bridge is markedly slower than the
        // equivalent warmup cost already seen on a native JVM (FaceROIStrategyTest/
        // StreamProcessorTest's own native-warmup notes).
        val received = withTimeout(15_000) { stream.first() }
        assertNotNull("Client should yield results from the processor", received)

        delay(100)
        assertEquals("Camera should have been started", 1, fakeCamera.startCallCount)

        client.stopStream()
        delay(100)

        assertEquals("Camera should have been stopped", 1, fakeCamera.stopCallCount)
    }

    @Test
    fun backgroundingAppPausesCameraAndForegroundingResumesIt() = runBlocking {
        val strategy = FakeInferenceStrategy()
        val roiStrategy = FakeROIStrategy()
        val fakeCamera = FakeCameraStreaming()
        val processor = StreamProcessor(strategy = strategy, camera = fakeCamera, roiStrategy = roiStrategy)

        val firstScenario = ActivityScenario.launch(EmptyTestActivity::class.java)
        // Constructing VitalLens while the activity is already RESUMED means LifecycleRegistry
        // synchronously delivers a "catch-up" onStart() to our observer during addObserver()
        // itself (it always brings a newly added observer up to the process's current state) —
        // firing handleAppForeground() once before any real backgrounding happens. This is
        // correct, expected ProcessLifecycleOwner behavior, not a StreamProcessor/VitalLens bug,
        // so assert relative increases from a snapshot taken after startStream() rather than
        // assuming a clean absolute call count.
        val client = VitalLens(context, processor)
        delay(150)

        client.startStream()
        delay(100)
        val startCountAfterStarting = fakeCamera.startCallCount
        assertTrue("Camera should have started", startCountAfterStarting > 0)
        val stopCountBeforeBackgrounding = fakeCamera.stopCallCount

        // moveToState(CREATED) from RESUMED doesn't reliably propagate through
        // ActivityLifecycleCallbacks.onActivityStopped() on this androidx.test version (verified
        // empirically: onPause/onStop fire for a full DESTROYED teardown but never for CREATED)
        // — destroy and then launch a fresh activity for a reliable ON_STOP -> ON_START cycle.
        // ProcessLifecycleOwner also intentionally debounces ON_STOP by ~700ms internally (to
        // absorb quick activity recreation, e.g. rotation) before actually dispatching it — wait
        // comfortably past that.
        firstScenario.moveToState(Lifecycle.State.DESTROYED)
        delay(1500)
        assertTrue(
            "Camera should stop on background",
            fakeCamera.stopCallCount > stopCountBeforeBackgrounding,
        )

        ActivityScenario.launch(EmptyTestActivity::class.java).use {
            delay(300)
            assertTrue(
                "Camera should restart on foreground",
                fakeCamera.startCallCount > startCountAfterStarting,
            )
        }
    }

    @Test
    fun publicPropertiesAreSetFromTheConstructor() {
        val client = VitalLens(context, apiKey = "key", method = "vitallens-2.0")
        assertEquals("key", client.apiKey)
        assertEquals("vitallens-2.0", client.method)
    }

    @Test
    fun faceStateCallbackSetBeforeStartingPropagatesToProcessor() = runBlocking {
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
