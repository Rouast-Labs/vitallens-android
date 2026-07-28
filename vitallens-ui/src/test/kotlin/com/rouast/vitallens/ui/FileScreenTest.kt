package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.Vital
import com.rouast.vitallens.inference.model.VitalLensResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileScreenTest {

    @Test
    fun `resolveFileVitals includes only vitals with a non-null value`() {
        val result = VitalLensResult(
            face = FaceData(),
            vitals = mapOf("heart_rate" to Vital(value = 65.0, confidence = 0.9)),
            waveforms = emptyMap(),
            time = List(300) { it / 30.0 },
            sampleCount = 300,
            fps = 30.0,
        )

        val (primary, secondary, _) = resolveFileVitals(result, fallbackFps = 30.0)

        assertEquals(1, primary.size)
        assertEquals("hr", primary[0].id)
        assertEquals(65.0, primary[0].value)
        assertTrue(secondary.isEmpty())
    }

    @Test
    fun `resolveFileVitals drops primary vitals that were never computed`() {
        val result = VitalLensResult(
            face = FaceData(),
            vitals = emptyMap(),
            waveforms = emptyMap(),
            time = List(300) { it / 30.0 },
            sampleCount = 300,
            fps = 30.0,
        )

        val (primary, _, _) = resolveFileVitals(result, fallbackFps = 30.0)

        assertTrue(primary.isEmpty())
    }

    @Test
    fun `resolveFileVitals includes secondary vitals without a confidence threshold`() {
        val result = VitalLensResult(
            face = FaceData(),
            vitals = mapOf(
                "hrv_sdnn" to Vital(value = 42.0, confidence = 0.1),
                "ie_ratio" to Vital(value = 0.35, confidence = 0.2),
            ),
            waveforms = emptyMap(),
            time = List(300) { it / 30.0 },
            sampleCount = 300,
            fps = 30.0,
        )

        val (_, secondary, _) = resolveFileVitals(result, fallbackFps = 30.0)

        assertEquals(2, secondary.size)
        val ieRatio = secondary.first { it.id == "ie_ratio" }
        assertEquals("%.2f", ieRatio.format)
        val sdnn = secondary.first { it.id == "hrv_sdnn" }
        assertEquals("%.0f", sdnn.format)
    }

    @Test
    fun `resolveFileVitals computes duration from sample count and result fps`() {
        val result = VitalLensResult(
            face = FaceData(confidence = listOf(0.8, 1.0)),
            vitals = emptyMap(),
            waveforms = emptyMap(),
            time = emptyList(),
            sampleCount = 300,
            fps = 30.0,
        )

        val (_, _, stats) = resolveFileVitals(result, fallbackFps = 15.0)

        assertEquals(10.0, stats.duration, 0.0001)
        assertEquals(300, stats.sampleCount)
        assertEquals(0.9, stats.avgFaceConf, 0.0001)
    }

    @Test
    fun `resolveFileVitals falls back to the given fps when the result has none`() {
        val result = VitalLensResult(
            face = FaceData(),
            vitals = emptyMap(),
            waveforms = emptyMap(),
            time = emptyList(),
            sampleCount = 150,
            fps = null,
        )

        val (_, _, stats) = resolveFileVitals(result, fallbackFps = 15.0)

        assertEquals(10.0, stats.duration, 0.0001)
    }
}
