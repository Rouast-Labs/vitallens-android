package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Thin verification test alongside the port, per CLAUDE.md's TDD exception for
// declarative error types with no meaningful red state. Exercises equality
// semantics only — exact message wording is intentionally not pinned by tests,
// so message strings can be edited without touching this file.
class ErrorTypesTest {

    @Test
    fun `invalidAPIKey is equal to itself`() {
        assertEquals(VitalLensException.InvalidAPIKey, VitalLensException.InvalidAPIKey)
    }

    @Test
    fun `quotaExceeded is equal to itself`() {
        assertEquals(VitalLensException.QuotaExceeded, VitalLensException.QuotaExceeded)
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
            VitalLensException.ProcessingError("invalidAPIKey"),
        )
        assertNotEquals(
            VitalLensException.ServerError(400, "x"),
            VitalLensException.ClientError(400, "x"),
        )
    }
}
