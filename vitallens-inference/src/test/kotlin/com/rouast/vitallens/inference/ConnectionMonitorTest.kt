package com.rouast.vitallens.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionMonitorTest {

    private fun monitor() = ConnectionMonitor(
        medianMs = 1000.0,
        singleMs = 2000.0,
        maxFrames = 50,
        window = 5,
        minSamples = 3,
    )

    @Test
    fun `does not fire while the connection is fast`() {
        val monitor = monitor()
        repeat(20) { assertNull(monitor.record(ms = 250.0, frames = 8)) }
    }

    @Test
    fun `fires when the median exceeds the limit`() {
        val monitor = monitor()
        assertNull(monitor.record(1500.0, 10))
        assertNull(monitor.record(1200.0, 10)) // not enough samples yet
        assertEquals(SlowConnectionInfo(medianMs = 1200.0, frames = 10), monitor.record(1100.0, 10))
    }

    @Test
    fun `ignores a single moderately slow request`() {
        val monitor = monitor()
        listOf(250.0, 300.0, 1900.0, 260.0, 240.0).forEach { assertNull(monitor.record(it, 8)) }
    }

    @Test
    fun `fires on the very first request if it is very slow`() {
        assertEquals(SlowConnectionInfo(medianMs = 3110.0, frames = 16), monitor().record(3110.0, 16))
    }

    @Test
    fun `fires on the first request if it carries too many frames`() {
        assertNotNull(monitor().record(300.0, 60))
    }

    @Test
    fun `only considers the most recent requests`() {
        val monitor = monitor()
        repeat(5) { assertNull(monitor.record(300.0, 8)) }
        assertNull(monitor.record(1500.0, 8))
        assertNull(monitor.record(1500.0, 8)) // window median is still 300
        assertNotNull(monitor.record(1500.0, 8)) // 300, 300, 1500, 1500, 1500
    }

    @Test
    fun `fires only once`() {
        val monitor = monitor()
        assertNotNull(monitor.record(3000.0, 8))
        repeat(10) { assertNull(monitor.record(3000.0, 8)) }
    }

    @Test
    fun `can be reset to start over`() {
        val monitor = monitor()
        assertNotNull(monitor.record(3000.0, 8))
        monitor.reset()
        assertNotNull(monitor.record(3000.0, 8))
    }
}
