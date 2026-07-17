package com.rouast.vitallens.inference

import android.graphics.Bitmap
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class LocalInferenceBaseTest {

    private data class FakeState(val id: String) : InferenceState

    private class FakeLocalInference(
        config: ModelConfig,
        private val onPredict: (List<Bitmap>, InferenceState?) -> InferenceOutcome,
    ) : LocalInferenceBase(config) {
        override suspend fun predict(frames: List<Bitmap>, state: InferenceState?): InferenceOutcome =
            onPredict(frames, state)
    }

    private fun createConfig(): ModelConfig = ModelConfig(
        nInputs = 4,
        inputSize = 40,
        fpsTarget = 30.0,
        roiMethod = "face",
        supportedVitals = listOf("heart_rate"),
    )

    private fun dummyResult(): VitalLensResult = VitalLensResult(
        face = FaceData(coordinates = null, confidence = null, note = null),
        vitals = emptyMap(),
        waveforms = emptyMap(),
        time = emptyList(),
    )

    @Test
    fun `resolveConfig returns the config it was constructed with`() = runTest {
        val config = createConfig()
        val strategy = FakeLocalInference(config) { _, state -> InferenceOutcome(dummyResult(), state) }
        assertEquals(config, strategy.resolveConfig())
    }

    @Test
    fun `bufferConfig delegates to the real Rust computeBufferConfig`() = runTest {
        val strategy = FakeLocalInference(createConfig()) { _, state -> InferenceOutcome(dummyResult(), state) }
        val bufferConfig = strategy.bufferConfig()
        assertTrue(bufferConfig.streamMax > 0u)
    }

    @Test
    fun `infer unwraps pixelBuffer frames and forwards them to predict`() = runTest {
        val bitmap = mock<Bitmap>()
        val result = dummyResult()
        var received: List<Bitmap>? = null
        val strategy = FakeLocalInference(createConfig()) { frames, state ->
            received = frames
            InferenceOutcome(result, state)
        }

        val window = listOf(InferenceUnit.PixelBuffer(bitmap) to InferenceContext(timestamp = 1.0))
        val outcome = strategy.infer(window, state = null, mode = InferenceMode.STREAM, model = null)

        assertEquals(1, received?.size)
        assertSame(bitmap, received?.first())
        assertSame(result, outcome.result)
    }

    @Test
    fun `infer passes state through to predict and returns predict's outcome`() = runTest {
        val result = dummyResult()
        val newState = FakeState("next")
        val strategy = FakeLocalInference(createConfig()) { _, _ -> InferenceOutcome(result, newState) }

        val outcome = strategy.infer(emptyList(), state = FakeState("prev"), mode = InferenceMode.STREAM, model = null)

        assertSame(result, outcome.result)
        assertSame(newState, outcome.newState)
    }

    @Test
    fun `infer throws ProcessingError when the window contains non-pixelBuffer input`() = runTest {
        val strategy = FakeLocalInference(createConfig()) { _, state -> InferenceOutcome(dummyResult(), state) }
        val window = listOf(InferenceUnit.RgbData(ByteArray(4)) to InferenceContext(timestamp = 1.0))

        var thrown: VitalLensException.ProcessingError? = null
        try {
            strategy.infer(window, state = null, mode = InferenceMode.STREAM, model = null)
        } catch (e: VitalLensException.ProcessingError) {
            thrown = e
        }
        assertNotNull(thrown)
    }
}
