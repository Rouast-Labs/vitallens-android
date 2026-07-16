package com.rouast.vitallens.inference.buffer

import com.rouast.vitallens.core.InferenceCommand
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.network.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameBufferTest {

    private fun createConfig(fps: Double = 30.0): ModelConfig = ModelConfig(
        nInputs = 4,
        inputSize = 40,
        fpsTarget = fps,
        roiMethod = "face",
        supportedVitals = listOf("heart_rate"),
    )

    private fun makeTrackedFrame(index: Int, time: Double? = null): Pair<InferenceUnit, InferenceContext> {
        val data = ByteArray(10)
        data[0] = (index % 255).toByte()
        val timestamp = time ?: index.toDouble()
        return InferenceUnit.RgbData(data) to InferenceContext(timestamp = timestamp)
    }

    private fun firstByte(unit: InferenceUnit): Int = (unit as InferenceUnit.RgbData).data[0].toInt()

    @Test
    fun `initialization sets id roi createdAt and starts empty`() {
        val config = createConfig()
        val roi = Rect(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f)
        val buffer = FrameBuffer(id = "buf1", roi = roi, mode = InferenceMode.STREAM, config = config, createdAt = 1.0)

        assertEquals("buf1", buffer.id)
        assertEquals(roi, buffer.roi)
        assertEquals(1.0, buffer.createdAt, 0.0001)
        assertEquals(0, buffer.count)
    }

    @Test
    fun `append updates count and lastSeen`() {
        val buffer = FrameBuffer(
            id = "buf1",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(),
            createdAt = 1.0,
        )
        val (unit, context) = makeTrackedFrame(index = 0, time = 1.5)

        buffer.append(unit, context)

        assertEquals(1, buffer.count)
        assertEquals(1.5, buffer.lastSeen, 0.0001)
    }

    @Test
    fun `capacity differs by mode`() {
        val streamBuf = FrameBuffer(
            id = "s",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(fps = 30.0),
            createdAt = 0.0,
        )
        val fileBuf = FrameBuffer(
            id = "f",
            roi = Rect.ZERO,
            mode = InferenceMode.FILE,
            config = createConfig(fps = 30.0),
            createdAt = 0.0,
        )

        // Stream buffer capacity at 30fps: max(150, 30*10) = 300
        repeat(350) { i -> val (unit, context) = makeTrackedFrame(i); streamBuf.append(unit, context) }
        // File buffer capacity is fixed at 1000, well above 350
        repeat(350) { i -> val (unit, context) = makeTrackedFrame(i); fileBuf.append(unit, context) }

        assertEquals(300, streamBuf.count)
        assertEquals(350, fileBuf.count)
    }

    @Test
    fun `execute with a valid command returns and trims the window`() {
        val buffer = FrameBuffer(
            id = "buf1",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(),
            createdAt = 0.0,
        )
        repeat(10) { i -> val (unit, context) = makeTrackedFrame(i); buffer.append(unit, context) }

        val payload = buffer.execute(InferenceCommand("buf1", 6u, 2u))

        assertEquals(6, payload?.size)
        assertEquals(6, buffer.count) // 10 - (6-2)
    }

    @Test
    fun `execute returns null when there are not enough frames`() {
        val buffer = FrameBuffer(
            id = "buf1",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(),
            createdAt = 0.0,
        )
        repeat(5) { i -> val (unit, context) = makeTrackedFrame(i); buffer.append(unit, context) }

        assertNull(buffer.execute(InferenceCommand("buf1", 10u, 2u)))
    }

    @Test
    fun `execute maintains correct data and overlap across successive calls`() {
        val buffer = FrameBuffer(
            id = "buf",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(),
            createdAt = 0.0,
        )
        repeat(10) { i -> val (unit, context) = makeTrackedFrame(i); buffer.append(unit, context) }

        val payload1 = buffer.execute(InferenceCommand("buf", 6u, 2u))!!
        // First batch: 0,1,2,3,4,5
        assertEquals(0, firstByte(payload1.first().first))
        assertEquals(5, firstByte(payload1.last().first))

        // Buffer now has 4,5,6,7,8,9
        val payload2 = buffer.execute(InferenceCommand("buf", 4u, 0u))!!
        // Second batch: 4,5,6,7 — should start with the overlap index
        assertEquals(4, firstByte(payload2.first().first))
    }

    @Test
    fun `overflow drops the oldest frames`() {
        val buffer = FrameBuffer(
            id = "buf",
            roi = Rect.ZERO,
            mode = InferenceMode.STREAM,
            config = createConfig(fps = 10.0),
            createdAt = 0.0,
        )
        // Capacity for 10fps should be 150
        repeat(200) { i -> val (unit, context) = makeTrackedFrame(i); buffer.append(unit, context) }

        assertEquals(150, buffer.count)

        val payload = buffer.execute(InferenceCommand("buf", 1u, 0u))!!
        // Should have dropped the first 50 frames
        assertEquals(50, firstByte(payload.first().first))
    }
}
