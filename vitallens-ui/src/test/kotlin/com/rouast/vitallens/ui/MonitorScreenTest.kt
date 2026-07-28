package com.rouast.vitallens.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorScreenTest {

    @Test
    fun `resolveMonitorFeedback flags low confidence when no vital is confident`() {
        val (state, message) = resolveMonitorFeedback(
            hasConfidentHr = false,
            hasConfidentRr = false,
            hasConfidentHrv = false,
            showWaveforms = true,
            hasEnoughData = true,
        )
        assertEquals(MonitorState.ISSUE, state)
        assertEquals("Low confidence. Ensure you are well lit and hold still.", message)
    }

    @Test
    fun `resolveMonitorFeedback is satisfied by hrv confidence alone`() {
        val (state, _) = resolveMonitorFeedback(
            hasConfidentHr = false,
            hasConfidentRr = false,
            hasConfidentHrv = true,
            showWaveforms = true,
            hasEnoughData = true,
        )
        assertEquals(MonitorState.TRACKING, state)
    }

    @Test
    fun `resolveMonitorFeedback warms up while waveforms are shown but data is insufficient`() {
        val (state, message) = resolveMonitorFeedback(
            hasConfidentHr = true,
            hasConfidentRr = false,
            hasConfidentHrv = false,
            showWaveforms = true,
            hasEnoughData = false,
        )
        assertEquals(MonitorState.WARMING_UP, state)
        assertEquals("", message)
    }

    @Test
    fun `resolveMonitorFeedback tracks once a vital is confident and data is sufficient`() {
        val (state, message) = resolveMonitorFeedback(
            hasConfidentHr = true,
            hasConfidentRr = false,
            hasConfidentHrv = false,
            showWaveforms = true,
            hasEnoughData = true,
        )
        assertEquals(MonitorState.TRACKING, state)
        assertEquals("Tracking vitals", message)
    }

    @Test
    fun `resolveMonitorFeedback ignores the data-sufficiency gate when waveforms are hidden`() {
        val (state, _) = resolveMonitorFeedback(
            hasConfidentHr = true,
            hasConfidentRr = false,
            hasConfidentHrv = false,
            showWaveforms = false,
            hasEnoughData = false,
        )
        assertEquals(MonitorState.TRACKING, state)
    }

    @Test
    fun `dynamicTileWidth is null when waveforms are hidden`() {
        assertNull(dynamicTileWidth(showWaveforms = false, hasSecondaryVitals = true))
    }

    @Test
    fun `dynamicTileWidth widens the tile when secondary vitals are present`() {
        assertEquals(170, dynamicTileWidth(showWaveforms = true, hasSecondaryVitals = true))
    }

    @Test
    fun `dynamicTileWidth narrows the tile without secondary vitals`() {
        assertEquals(110, dynamicTileWidth(showWaveforms = true, hasSecondaryVitals = false))
    }

    @Test
    fun `monitorVitalFormat uses two decimals for ie_ratio and hrv_lfhf`() {
        assertEquals("%.2f", monitorVitalFormat("ie_ratio"))
        assertEquals("%.2f", monitorVitalFormat("hrv_lfhf"))
    }

    @Test
    fun `monitorVitalFormat uses whole numbers for everything else`() {
        assertEquals("%.0f", monitorVitalFormat("heart_rate"))
        assertEquals("%.0f", monitorVitalFormat(null))
    }
}
