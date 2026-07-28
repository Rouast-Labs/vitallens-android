package com.rouast.vitallens.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanScreenTest {

    @Test
    fun `searching transitions to warmingUp once a good face is found`() {
        val result = resolveScanTransition(
            currentState = ScanState.SEARCHING,
            currentMessage = "Position your face in the oval",
            goodFace = true,
            isLowSignal = false,
            elapsedInState = 0.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.WARMING_UP, result.state)
        assertEquals("Calibrating... Hold still.", result.message)
        assertEquals(0, result.strikeCount)
    }

    @Test
    fun `searching stays searching and keeps the existing message without a good face`() {
        val result = resolveScanTransition(
            currentState = ScanState.SEARCHING,
            currentMessage = "Position your face in the oval",
            goodFace = false,
            isLowSignal = false,
            elapsedInState = 0.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.SEARCHING, result.state)
        assertEquals("Position your face in the oval", result.message)
    }

    @Test
    fun `warmingUp stays warmingUp before the warm-up duration elapses`() {
        val result = resolveScanTransition(
            currentState = ScanState.WARMING_UP,
            currentMessage = "Calibrating... Hold still.",
            goodFace = true,
            isLowSignal = false,
            elapsedInState = 1.5,
            strikeCount = 0,
            warmUpDuration = 3.0,
        )
        assertEquals(ScanState.WARMING_UP, result.state)
        assertEquals("Calibrating... Hold still.", result.message)
    }

    @Test
    fun `warmingUp transitions to tracking once the warm-up duration elapses`() {
        val result = resolveScanTransition(
            currentState = ScanState.WARMING_UP,
            currentMessage = "Calibrating... Hold still.",
            goodFace = true,
            isLowSignal = false,
            elapsedInState = 3.0,
            strikeCount = 0,
            warmUpDuration = 3.0,
        )
        assertEquals(ScanState.TRACKING, result.state)
        assertEquals("Scanning...", result.message)
    }

    @Test
    fun `tracking stays tracking while the face is good and signal is strong`() {
        val result = resolveScanTransition(
            currentState = ScanState.TRACKING,
            currentMessage = "Scanning...",
            goodFace = true,
            isLowSignal = false,
            elapsedInState = 5.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.TRACKING, result.state)
        assertEquals("Scanning...", result.message)
    }

    @Test
    fun `tracking transitions to recovering when the face is no longer good`() {
        val result = resolveScanTransition(
            currentState = ScanState.TRACKING,
            currentMessage = "Scanning...",
            goodFace = false,
            isLowSignal = false,
            elapsedInState = 5.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.RECOVERING, result.state)
        assertEquals("Adjust position...", result.message)
    }

    @Test
    fun `tracking transitions to recovering when the signal is low`() {
        val result = resolveScanTransition(
            currentState = ScanState.TRACKING,
            currentMessage = "Scanning...",
            goodFace = true,
            isLowSignal = true,
            elapsedInState = 5.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.RECOVERING, result.state)
        assertEquals("Improve lighting...", result.message)
    }

    @Test
    fun `recovering transitions back to tracking once conditions recover`() {
        val result = resolveScanTransition(
            currentState = ScanState.RECOVERING,
            currentMessage = "Adjust position...",
            goodFace = true,
            isLowSignal = false,
            elapsedInState = 2.0,
            strikeCount = 0,
        )
        assertEquals(ScanState.TRACKING, result.state)
        assertEquals("Scanning...", result.message)
    }

    @Test
    fun `recovering keeps prompting to adjust position while the face is still bad`() {
        val result = resolveScanTransition(
            currentState = ScanState.RECOVERING,
            currentMessage = "Adjust position...",
            goodFace = false,
            isLowSignal = false,
            elapsedInState = 2.0,
            strikeCount = 0,
            recoveryTimeout = 10.0,
        )
        assertEquals(ScanState.RECOVERING, result.state)
        assertEquals("Adjust position...", result.message)
    }

    @Test
    fun `recovering prompts to improve lighting when only the signal is low`() {
        val result = resolveScanTransition(
            currentState = ScanState.RECOVERING,
            currentMessage = "Improve lighting...",
            goodFace = true,
            isLowSignal = true,
            elapsedInState = 2.0,
            strikeCount = 0,
            recoveryTimeout = 10.0,
        )
        assertEquals(ScanState.RECOVERING, result.state)
        assertEquals("Improve lighting...", result.message)
    }

    @Test
    fun `recovering escalates to a retry via searching when the timeout elapses with strikes to spare`() {
        val result = resolveScanTransition(
            currentState = ScanState.RECOVERING,
            currentMessage = "Adjust position...",
            goodFace = false,
            isLowSignal = false,
            elapsedInState = 10.0,
            strikeCount = 0,
            recoveryTimeout = 10.0,
        )
        assertEquals(ScanState.SEARCHING, result.state)
        assertEquals("Could not recover conditions. Retrying...", result.message)
        assertEquals(1, result.strikeCount)
    }

    @Test
    fun `recovering escalates to a hard issue once strikes are exhausted`() {
        val result = resolveScanTransition(
            currentState = ScanState.RECOVERING,
            currentMessage = "Adjust position...",
            goodFace = false,
            isLowSignal = false,
            elapsedInState = 10.0,
            strikeCount = 2,
            recoveryTimeout = 10.0,
        )
        assertEquals(ScanState.ISSUE, result.state)
        assertEquals("Could not recover conditions.", result.message)
        assertEquals(3, result.strikeCount)
    }

    @Test
    fun `resolveIssueEscalation retries via searching below the strike limit`() {
        val result = resolveIssueEscalation(strikeCount = 0, message = "Face lost.")
        assertEquals(ScanState.SEARCHING, result.state)
        assertEquals("Face lost. Retrying...", result.message)
        assertEquals(1, result.strikeCount)
    }

    @Test
    fun `resolveIssueEscalation escalates to a hard issue at the strike limit`() {
        val result = resolveIssueEscalation(strikeCount = 2, message = "Face lost.")
        assertEquals(ScanState.ISSUE, result.state)
        assertEquals("Face lost.", result.message)
        assertEquals(3, result.strikeCount)
    }
}
