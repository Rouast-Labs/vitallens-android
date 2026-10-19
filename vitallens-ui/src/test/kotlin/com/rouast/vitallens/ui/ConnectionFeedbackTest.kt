package com.rouast.vitallens.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFeedbackTest {

    @Test
    fun `messages match the other SDKs`() {
        assertEquals("Low confidence.\nUse steady light, move closer, and hold still.", LOW_CONFIDENCE_MESSAGE)
        assertEquals("Slow connection. Switched to Eco mode.", SLOW_CONNECTION_MESSAGE)
    }

    @Test
    fun `a notice is not overwritten by regular messages`() {
        assertFalse(shouldShowMessage(nowMs = 1_000, noticeUntilMs = 5_000, force = false))
    }

    @Test
    fun `a notice does not block forced messages such as issues`() {
        assertTrue(shouldShowMessage(nowMs = 1_000, noticeUntilMs = 5_000, force = true))
    }

    @Test
    fun `messages show again once the notice has expired`() {
        assertTrue(shouldShowMessage(nowMs = 5_000, noticeUntilMs = 5_000, force = false))
        assertTrue(shouldShowMessage(nowMs = 9_000, noticeUntilMs = 5_000, force = false))
    }

    @Test
    fun `stalled only when waiting for results for longer than the timeout`() {
        assertFalse(isStalled(waitingForResults = true, msSinceLastResult = 7_999))
        assertTrue(isStalled(waitingForResults = true, msSinceLastResult = 8_001))
    }

    @Test
    fun `never stalled when no results are expected`() {
        assertFalse(isStalled(waitingForResults = false, msSinceLastResult = 60_000))
    }

    @Test
    fun `the default mode is standard`() {
        assertEquals(VitalLensMode.STANDARD, DEFAULT_VITALLENS_MODE)
    }

    @Test
    fun `a cancelled session is not reported as an error`() = runBlocking {
        var error: Exception? = null
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runSession(onError = { error = it }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertEquals(null, error)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a failing session is reported as an error`() = runBlocking {
        var error: Exception? = null
        runSession(onError = { error = it }) { throw IllegalStateException("boom") }

        assertEquals("boom", error?.message)
    }

    @Test
    fun `cancellation inside the session still propagates`() = runBlocking {
        var propagated = false
        try {
            runSession(onError = {}) { throw CancellationException("stop") }
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
