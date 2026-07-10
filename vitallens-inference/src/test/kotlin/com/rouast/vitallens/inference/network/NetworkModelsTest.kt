package com.rouast.vitallens.inference.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// No dedicated NetworkModelsTests.swift exists in vitallens-ios. This is ported from
// the decode behavior exercised by Tests/VitalLensInferenceTests/APIInferenceTests.swift's
// `resolveResponse` / `emptySuccessResponse` JSON fixtures (see testResolveModelQueryParam,
// testStrategyConformance), plus the encode/exclusion behavior implied directly by
// Sources/VitalLensInference/NetworkModels.swift's custom CodingKeys (modelName is not
// listed there, so Swift's Codable synthesis never reads or writes it), and the
// APIErrorResponse.message decode usage at APIInference.swift:350.
class NetworkModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Ported from APIInferenceTests.swift `resolveResponse` fixture + assertions in
    // testResolveModelQueryParam/testStrategyConformance.
    @Test
    fun `decodes resolve-model response with populated config`() {
        val payload = """
            {
                "resolved_model": "vitallens-2.0",
                "config": {
                    "n_inputs": 4,
                    "input_size": 40,
                    "fps_target": 30.0,
                    "roi_method": "face",
                    "supported_vitals": ["heart_rate"]
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ResolveModelResponse>(payload)

        assertEquals("vitallens-2.0", response.resolvedModel)
        assertEquals(4, response.config.nInputs)
        assertEquals(40, response.config.inputSize)
        assertEquals(30.0, response.config.fpsTarget, 0.0001)
        assertEquals("face", response.config.roiMethod)
        assertEquals(listOf("heart_rate"), response.config.supportedVitals)
    }

    // Ported from APIInferenceTests.swift `emptySuccessResponse` fixture, used as the
    // generic "any successful call" stub across most of that file's other tests.
    @Test
    fun `decodes resolve-model response with empty defaults`() {
        val payload = """
            { "resolved_model": "test", "config": { "n_inputs": 0, "input_size": 0, "fps_target": 0, "roi_method": "", "supported_vitals": [] } }
        """.trimIndent()

        val response = json.decodeFromString<ResolveModelResponse>(payload)

        assertEquals("test", response.resolvedModel)
        assertEquals(0, response.config.nInputs)
        assertEquals(0, response.config.inputSize)
        assertEquals(0.0, response.config.fpsTarget, 0.0001)
        assertEquals("", response.config.roiMethod)
        assertEquals(emptyList<String>(), response.config.supportedVitals)
    }

    // NetworkModels.swift's ModelConfig.CodingKeys omits modelName, so Swift's Codable
    // synthesis never decodes it from JSON — it always keeps its "vitallens" default.
    @Test
    fun `modelName is not decoded from JSON and keeps its default`() {
        val payload = """
            { "resolved_model": "test", "config": { "n_inputs": 1, "input_size": 1, "fps_target": 1.0, "roi_method": "face", "supported_vitals": [] } }
        """.trimIndent()

        val response = json.decodeFromString<ResolveModelResponse>(payload)

        assertEquals("vitallens", response.config.modelName)
    }

    // Same exclusion applies on encode: modelName must not appear in the serialized JSON,
    // matching Swift's CodingKeys omission (which governs both directions).
    @Test
    fun `modelName is excluded from encoded JSON`() {
        val config = ModelConfig(
            nInputs = 1,
            inputSize = 1,
            fpsTarget = 1.0,
            roiMethod = "face",
            supportedVitals = emptyList(),
        )

        val encoded = json.encodeToString(config)

        assertFalse(encoded.contains("model_name"))
        assertFalse(encoded.contains("modelName"))
    }

    @Test
    fun `ModelConfig encodes fields with snake_case keys`() {
        val config = ModelConfig(
            nInputs = 4,
            inputSize = 40,
            fpsTarget = 30.0,
            roiMethod = "face",
            supportedVitals = listOf("heart_rate"),
        )

        val encoded = json.encodeToString(config)

        assertTrue(encoded.contains("\"n_inputs\":4"))
        assertTrue(encoded.contains("\"input_size\":40"))
        assertTrue(encoded.contains("\"fps_target\":30.0"))
        assertTrue(encoded.contains("\"roi_method\":\"face\""))
        assertTrue(encoded.contains("\"supported_vitals\":[\"heart_rate\"]"))
    }

    @Test
    fun `ResolveModelResponse encodes resolved_model key`() {
        val response = ResolveModelResponse(
            resolvedModel = "vitallens-2.0",
            config = ModelConfig(
                nInputs = 4,
                inputSize = 40,
                fpsTarget = 30.0,
                roiMethod = "face",
                supportedVitals = listOf("heart_rate"),
            ),
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"resolved_model\":\"vitallens-2.0\""))
    }

    // Ported from APIInference.swift:350's usage: JSONDecoder().decode(APIErrorResponse.self, ...).message
    @Test
    fun `decodes API error response message`() {
        val payload = """{ "message": "invalid request" }"""

        val error = json.decodeFromString<APIErrorResponse>(payload)

        assertEquals("invalid request", error.message)
    }

    @Test
    fun `decodes API error response with missing message as null`() {
        val payload = """{}"""

        val error = json.decodeFromString<APIErrorResponse>(payload)

        assertNull(error.message)
    }
}
