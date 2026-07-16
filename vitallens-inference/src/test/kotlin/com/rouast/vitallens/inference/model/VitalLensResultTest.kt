package com.rouast.vitallens.inference.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalLensResultTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `dynamic decoding routes waveforms and vitals dictionaries by key`() {
        val raw = """
        {
            "face": {
                "coordinates": [[0.1, 0.1, 0.2, 0.2]],
                "confidence": [0.99],
                "note": "Face found"
            },
            "waveforms": {
                "ppg_waveform": {
                    "data": [0.5, 0.6, 0.7],
                    "confidence": [1.0, 1.0, 1.0],
                    "unit": "unitless",
                    "note": ""
                }
            },
            "vitals": {
                "sbp": {
                    "value": 121.0,
                    "confidence": 0.8,
                    "unit": "mmHg",
                    "note": "Experimental"
                }
            },
            "time": [1.0, 1.03, 1.06],
            "fps": 30.0,
            "message": "OK",
            "model_used": "vitallens_v3_hybrid",
            "n": 3
        }
        """.trimIndent()

        val result = json.decodeFromString(VitalLensResultSerializer, raw)

        assertEquals(30.0, result.fps!!, 0.0001)
        assertEquals("OK", result.message)
        assertEquals("vitallens_v3_hybrid", result.modelUsed)
        assertEquals(3, result.sampleCount)
        assertEquals(1, result.face.coordinates?.size)

        assertNotNull(result.ppg)
        assertEquals(3, result.ppg?.data?.size)
        assertEquals(0.5f, result.ppg!!.data.first(), 0.001f)

        assertNotNull(result.vitals["sbp"])
        assertEquals("mmHg", result.vitals["sbp"]?.unit)
        assertEquals(121.0, result.vitals["sbp"]?.value ?: 0.0, 0.1)
    }

    @Test
    fun `state decodes polymorphically whether string or float array`() {
        val stringStateJson = """{"face": {}, "signals": {}, "time": [], "state": {"data": "SGVsbG8="}}"""
        val result1 = json.decodeFromString(VitalLensResultSerializer, stringStateJson)
        assertEquals("SGVsbG8=", result1.state?.data)

        val arrayStateJson = """{"face": {}, "signals": {}, "time": [], "state": {"data": [0.0, 0.0]}}"""
        val result2 = json.decodeFromString(VitalLensResultSerializer, arrayStateJson)
        assertEquals("AAAAAAAAAAA=", result2.state?.data)
    }

    @Test
    fun `missing signals decode as empty maps without crashing`() {
        val raw = """
        {
            "face": {"coordinates": [], "confidence": [], "note": ""},
            "vitals": {},
            "waveforms": {},
            "time": [1.0]
        }
        """.trimIndent()

        val result = json.decodeFromString(VitalLensResultSerializer, raw)

        assertTrue(result.vitals.isEmpty())
        assertTrue(result.waveforms.isEmpty())
        assertNull(result.ppg)
        assertNull(result.heartRate)
    }

    @Test
    fun `time is always empty on decode regardless of the raw payload`() {
        val raw = """{"face": {}, "vitals": {}, "waveforms": {}, "time": [1.0, 2.0, 3.0]}"""
        val result = json.decodeFromString(VitalLensResultSerializer, raw)
        assertTrue(result.time.isEmpty())
    }

    @Test
    fun `malformed individual vital or waveform entries are skipped, not fatal`() {
        val raw = """
        {
            "face": {}, "time": [],
            "vitals": {
                "sbp": {"value": 120.0, "confidence": 0.9, "unit": "mmHg"},
                "broken": "not an object"
            },
            "waveforms": {}
        }
        """.trimIndent()

        val result = json.decodeFromString(VitalLensResultSerializer, raw)

        assertEquals(1, result.vitals.size)
        assertNotNull(result.vitals["sbp"])
        assertNull(result.vitals["broken"])
    }

    @Test
    fun `vitals and waveforms route by their own dynamic keys`() {
        val raw = """
        {
            "face": {}, "time": [],
            "vitals": {
                "stress_index": {"value": 45.0, "confidence": 0.8, "unit": "pts"}
            },
            "waveforms": {
                "resp_signal": {"data": [0.1, 0.2], "confidence": [1.0, 1.0]}
            }
        }
        """.trimIndent()

        val result = json.decodeFromString(VitalLensResultSerializer, raw)

        assertNotNull(result.vitals["stress_index"])
        assertEquals(45.0, result.vitals["stress_index"]?.value ?: 0.0, 0.0001)
        assertNotNull(result.waveforms["resp_signal"])
        assertEquals(2, result.waveforms["resp_signal"]?.data?.size)
    }

    @Test
    fun `convenience accessors read from vitals and waveforms maps by key`() {
        val result = VitalLensResult(
            face = FaceData(coordinates = null, confidence = null, note = null),
            vitals = mapOf(
                "heart_rate" to Vital(value = 72.0, confidence = 1.0, unit = "bpm"),
                "hrv_sdnn" to Vital(value = 50.0, confidence = 0.8, unit = "ms"),
                "sbp" to Vital(value = 120.0, confidence = 0.9, unit = "mmHg"),
            ),
            waveforms = emptyMap(),
            time = listOf(1.0),
        )

        assertEquals(72.0, result.heartRate?.value ?: 0.0, 0.0001)
        assertEquals(50.0, result.hrvSdnn?.value ?: 0.0, 0.0001)
        assertEquals(120.0, result.sbp?.value ?: 0.0, 0.0001)
        assertNull(result.respiratoryRate)
    }

    @Test
    fun `encoding then decoding round-trips heart rate ppg and face note`() {
        val original = VitalLensResult(
            face = FaceData(coordinates = listOf(listOf(0.0, 0.0, 1.0, 1.0)), confidence = listOf(1.0), note = "test"),
            vitals = mapOf("heart_rate" to Vital(value = 70.0, confidence = 1.0, unit = "bpm")),
            waveforms = mapOf(
                "ppg_waveform" to Waveform(data = listOf(0.1f), confidence = listOf(1.0f), unit = null, note = null),
            ),
            time = listOf(1.0),
        )

        val encoded = json.encodeToString(VitalLensResultSerializer, original)
        val decoded = json.decodeFromString(VitalLensResultSerializer, encoded)

        assertEquals(70.0, decoded.heartRate?.value ?: 0.0, 0.0001)
        assertEquals(0.1f, decoded.ppg?.data?.first() ?: 0f, 0.001f)
        assertEquals("test", decoded.face.note)
    }
}
