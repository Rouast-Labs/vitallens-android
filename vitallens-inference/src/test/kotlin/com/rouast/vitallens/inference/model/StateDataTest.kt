package com.rouast.vitallens.inference.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes string data directly`() {
        val state = json.decodeFromString(StateDataSerializer, """{"data": "SGVsbG8="}""")
        assertEquals("SGVsbG8=", state.data)
        assertNull(state.note)
    }

    @Test
    fun `decodes float array data as little-endian base64`() {
        val state = json.decodeFromString(StateDataSerializer, """{"data": [0.0, 0.0]}""")
        assertEquals("AAAAAAAAAAA=", state.data)
    }

    @Test
    fun `decodes optional note alongside string data`() {
        val state = json.decodeFromString(StateDataSerializer, """{"data": "abc", "note": "hint"}""")
        assertEquals("hint", state.note)
    }

    @Test
    fun `encoding then decoding round-trips data and note`() {
        val original = StateData(data = "SGVsbG8=", note = "hint")
        val encoded = json.encodeToString(StateDataSerializer, original)
        val decoded = json.decodeFromString(StateDataSerializer, encoded)
        assertEquals(original, decoded)
    }
}
