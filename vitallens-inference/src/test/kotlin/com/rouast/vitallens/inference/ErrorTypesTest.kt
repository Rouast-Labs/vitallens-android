package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Thin verification test alongside the port, per CLAUDE.md's TDD exception for
// declarative error types with no meaningful red state. Message strings are
// ported verbatim from Errors.swift's `errorDescription`.
class ErrorTypesTest {

    @Test
    fun `invalidAPIKey has expected message and is equal to itself`() {
        assertEquals(
            "A valid VitalLens API key is required. Please check your configuration.",
            VitalLensException.InvalidAPIKey.message,
        )
        assertEquals(VitalLensException.InvalidAPIKey, VitalLensException.InvalidAPIKey)
    }

    @Test
    fun `quotaExceeded has expected message and is equal to itself`() {
        assertEquals(
            "VitalLens API quota exceeded. Please check your plan limits.",
            VitalLensException.QuotaExceeded.message,
        )
        assertEquals(VitalLensException.QuotaExceeded, VitalLensException.QuotaExceeded)
    }

    @Test
    fun `serverError formats message with status code and provided message`() {
        val error = VitalLensException.ServerError(statusCode = 500, serverMessage = "boom")
        assertEquals("VitalLens Server Error (500): boom", error.message)
    }

    @Test
    fun `serverError falls back to Unknown error when message is null`() {
        val error = VitalLensException.ServerError(statusCode = 503, serverMessage = null)
        assertEquals("VitalLens Server Error (503): Unknown error", error.message)
    }

    @Test
    fun `serverError equality is by statusCode and message`() {
        assertEquals(
            VitalLensException.ServerError(500, "boom"),
            VitalLensException.ServerError(500, "boom"),
        )
        assertNotEquals(
            VitalLensException.ServerError(500, "boom"),
            VitalLensException.ServerError(500, "different"),
        )
        assertNotEquals(
            VitalLensException.ServerError(500, "boom"),
            VitalLensException.ServerError(501, "boom"),
        )
    }

    @Test
    fun `clientError formats message with status code and provided message`() {
        val error = VitalLensException.ClientError(statusCode = 400, serverMessage = "nope")
        assertEquals("VitalLens Request Error (400): nope", error.message)
    }

    @Test
    fun `clientError falls back to Bad request when message is null`() {
        val error = VitalLensException.ClientError(statusCode = 422, serverMessage = null)
        assertEquals("VitalLens Request Error (422): Bad request", error.message)
    }

    @Test
    fun `clientError equality is by statusCode and message`() {
        assertEquals(
            VitalLensException.ClientError(400, "nope"),
            VitalLensException.ClientError(400, "nope"),
        )
        assertNotEquals(
            VitalLensException.ClientError(400, "nope"),
            VitalLensException.ClientError(401, "nope"),
        )
    }

    @Test
    fun `decodingError formats message from underlying cause`() {
        val error = VitalLensException.DecodingError(IllegalStateException("bad json"))
        assertEquals("Failed to parse API response: bad json", error.message)
    }

    @Test
    fun `decodingError equality is by underlying message, not instance`() {
        assertEquals(
            VitalLensException.DecodingError(IllegalStateException("bad json")),
            VitalLensException.DecodingError(RuntimeException("bad json")),
        )
        assertNotEquals(
            VitalLensException.DecodingError(IllegalStateException("bad json")),
            VitalLensException.DecodingError(IllegalStateException("other json")),
        )
    }

    @Test
    fun `networkError formats message from underlying cause`() {
        val error = VitalLensException.NetworkError(IllegalStateException("offline"))
        assertEquals("Network connection failed: offline", error.message)
    }

    @Test
    fun `networkError equality is by underlying message, not instance`() {
        assertEquals(
            VitalLensException.NetworkError(IllegalStateException("offline")),
            VitalLensException.NetworkError(RuntimeException("offline")),
        )
        assertNotEquals(
            VitalLensException.NetworkError(IllegalStateException("offline")),
            VitalLensException.NetworkError(IllegalStateException("timeout")),
        )
    }

    @Test
    fun `processingError formats message with detail`() {
        val error = VitalLensException.ProcessingError("frame buffer empty")
        assertEquals("Processing error: frame buffer empty", error.message)
    }

    @Test
    fun `processingError equality is by detail`() {
        assertEquals(
            VitalLensException.ProcessingError("frame buffer empty"),
            VitalLensException.ProcessingError("frame buffer empty"),
        )
        assertNotEquals(
            VitalLensException.ProcessingError("frame buffer empty"),
            VitalLensException.ProcessingError("other"),
        )
    }

    @Test
    fun `different case types are never equal`() {
        assertNotEquals(
            VitalLensException.InvalidAPIKey,
            VitalLensException.ProcessingError("A valid VitalLens API key is required. Please check your configuration."),
        )
        assertNotEquals(
            VitalLensException.ServerError(400, "x"),
            VitalLensException.ClientError(400, "x"),
        )
    }
}
