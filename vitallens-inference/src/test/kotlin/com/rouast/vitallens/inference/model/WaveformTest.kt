package com.rouast.vitallens.inference.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaveformTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes data confidence and unit`() {
        val wave = json.decodeFromString(
            Waveform.serializer(),
            """{"data": [1.0, 2.0], "confidence": [0.9, 0.8], "unit": "unitless"}""",
        )
        assertEquals(2, wave.data.size)
        assertEquals("unitless", wave.unit)
        assertNull(wave.note)
    }
}
