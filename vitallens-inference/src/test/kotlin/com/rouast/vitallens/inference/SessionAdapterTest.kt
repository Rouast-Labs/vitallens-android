package com.rouast.vitallens.inference

import com.rouast.vitallens.core.FaceResult
import com.rouast.vitallens.core.SessionResult
import com.rouast.vitallens.core.VitalResult
import com.rouast.vitallens.core.WaveformResult
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.StateData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.Waveform
import com.rouast.vitallens.inference.network.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAdapterTest {

    // Configuration Mapping

    @Test
    fun `ModelConfig maps to SessionConfig`() {
        val config = ModelConfig(
            nInputs = 4,
            inputSize = 40,
            fpsTarget = 30.5,
            roiMethod = "forehead",
            supportedVitals = listOf("heart_rate", "sbp"),
        )
        val rustConfig = config.toSessionConfig()

        assertEquals(4uL, rustConfig.nInputs)
        assertEquals(40uL, rustConfig.inputSize)
        assertEquals(30.5f, rustConfig.fpsTarget, 0.0001f)
        assertEquals("forehead", rustConfig.roiMethod)
        assertEquals(listOf("heart_rate", "sbp"), rustConfig.supportedVitals)
    }

    // Geometry Mapping

    @Test
    fun `Rect maps to the generated core Rect`() {
        val rect = Rect(x = 10.5f, y = 20.5f, width = 30.0f, height = 40.0f)
        val rustRect = rect.toRustRect()

        assertEquals(10.5f, rustRect.x, 0.0001f)
        assertEquals(20.5f, rustRect.y, 0.0001f)
        assertEquals(30.0f, rustRect.width, 0.0001f)
        assertEquals(40.0f, rustRect.height, 0.0001f)
    }

    // Result to Input Mapping

    @Test
    fun `VitalLensResult maps waveforms and timestamps into SessionInput signals`() {
        val wave = Waveform(data = listOf(1.0f, 2.0f), confidence = listOf(0.5f, 0.5f), unit = "bpm", note = null)
        val result = VitalLensResult(
            face = FaceData(coordinates = null, confidence = null, note = null),
            vitals = emptyMap(),
            waveforms = mapOf("custom_signal" to wave),
            time = listOf(100.0, 101.0),
        )

        val sessionInput = result.toSessionInput()

        assertEquals(listOf(100.0, 101.0), sessionInput.timestamp)
        assertEquals(listOf(1.0f, 2.0f), sessionInput.signals["custom_signal"]?.data)
    }

    @Test
    fun `VitalLensResult maps face presence and empty state correctly`() {
        val face = FaceData(coordinates = listOf(listOf(0.5, 0.25, 0.75, 1.0)), confidence = listOf(0.5), note = "ok")
        val resultWithFace = VitalLensResult(
            face = face,
            vitals = emptyMap(),
            waveforms = emptyMap(),
            time = emptyList(),
        )
        assertNotNull(resultWithFace.toSessionInput().face)

        val emptyResult = VitalLensResult(
            face = FaceData(coordinates = null, confidence = null, note = null),
            vitals = emptyMap(),
            waveforms = emptyMap(),
            time = emptyList(),
        )
        val emptyInput = emptyResult.toSessionInput()
        assertTrue(emptyInput.signals.isEmpty())
        assertNull(emptyInput.face)
    }

    // Session to Result Mapping

    @Test
    fun `SessionResult maps into VitalLensResult with overrides applied`() {
        val rustFace = FaceResult(listOf(listOf(0.1f, 0.1f, 0.2f, 0.2f)), listOf(0.9f), "rust_ok")
        val rustVital = VitalResult(60.0f, 0.5f, "bpm", "hr_note")
        val rustWave = WaveformResult(listOf(0.5f, 0.25f), listOf(1.0f, 1.0f), "u", "ppg_note")

        val sessionResult = SessionResult(
            timestamp = listOf(10.0, 11.0),
            face = rustFace,
            waveforms = mapOf("ppg_waveform" to rustWave),
            vitals = mapOf("heart_rate" to rustVital),
            rollingVitals = null,
            fps = 30.0f,
            message = "rust_msg",
        )

        val state = StateData(data = "b64", note = null)
        val vlResult = sessionResult.toVitalLensResult(originalState = state, message = "override", modelUsed = "test_model")

        assertEquals("override", vlResult.message)
        assertEquals("test_model", vlResult.modelUsed)
        assertEquals("b64", vlResult.state?.data)
        assertEquals(60.0, vlResult.heartRate?.value ?: 0.0, 0.0001)
        assertEquals(2, vlResult.ppg?.data?.size)
        assertEquals(2, vlResult.sampleCount)
    }

    @Test
    fun `SessionResult falls back to its own message and empty face when face and overrides are absent`() {
        val sessionResult = SessionResult(
            timestamp = listOf(10.0),
            face = null,
            waveforms = emptyMap(),
            vitals = emptyMap(),
            rollingVitals = null,
            fps = 30.0f,
            message = "msg",
        )
        val vlResult = sessionResult.toVitalLensResult(originalState = null, message = null, modelUsed = null)

        assertEquals("msg", vlResult.message)
        assertNull(vlResult.face.coordinates)
        assertTrue(vlResult.waveforms.isEmpty())
    }
}
