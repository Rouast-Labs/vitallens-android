package com.rouast.vitallens.inference.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decoding with only value present defaults confidence and unit`() {
        val vital = json.decodeFromString(Vital.serializer(), """{"value": 75.0}""")
        assertEquals(75.0, vital.value, 0.0001)
        assertEquals(0.0, vital.confidence, 0.0001)
        assertEquals("", vital.unit)
        assertNull(vital.note)
    }

    @Test
    fun `decoding with all fields present`() {
        val vital = json.decodeFromString(
            Vital.serializer(),
            """{"value": 121.0, "confidence": 0.8, "unit": "mmHg", "note": "Experimental"}""",
        )
        assertEquals(121.0, vital.value, 0.0001)
        assertEquals(0.8, vital.confidence, 0.0001)
        assertEquals("mmHg", vital.unit)
        assertEquals("Experimental", vital.note)
    }
}
