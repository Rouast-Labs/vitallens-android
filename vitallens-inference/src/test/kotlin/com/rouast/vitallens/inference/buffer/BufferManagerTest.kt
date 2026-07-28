package com.rouast.vitallens.inference.buffer

import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceCommand
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.network.ModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferManagerTest {

    private data class MockState(val id: String) : InferenceState

    private fun createConfig(): ModelConfig = ModelConfig(
        nInputs = 4,
        inputSize = 40,
        fpsTarget = 30.0,
        roiMethod = "face",
        supportedVitals = listOf("heart_rate"),
    )

    private fun createBufferConfig(): BufferConfig =
        BufferConfig(minNoState = 10u, minWithState = 4u, streamMax = 30u, fileMax = 100u, overlap = 3u)

    private fun createDummyUnit(): InferenceUnit = InferenceUnit.RgbData(ByteArray(10))

    private fun createDummyContext(time: Double): InferenceContext = InferenceContext(timestamp = time)

    private suspend fun makeInitializedManager(
        target: Rect? = null,
        targetTime: Double = 1.0,
    ): Pair<BufferManager, ModelConfig> {
        val manager = BufferManager()
        val config = createConfig()
        manager.initialize(createBufferConfig())
        if (target != null) {
            manager.registerTarget(target, targetTime, config)
        }
        return manager to config
    }

    // Initialization

    @Test
    fun `initialize sets up the planner`() = runTest {
        val (manager, _) = makeInitializedManager()
        val cmd = manager.poll(InferenceMode.STREAM)
        assertNull(cmd)
    }

    // Target Registration

    @Test
    fun `registerTarget creates a new buffer for a new target`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect(0.1f, 0.1f, 0.2f, 0.2f))
        val active = manager.getAllBuffers()

        assertEquals(1, active.size)
        assertFalse(active[0].id.isEmpty())
        assertEquals(0.1f, active[0].roi.x, 0.001f)
    }

    @Test
    fun `registerTarget keeps the existing buffer alive for a matching target`() = runTest {
        val (manager, config) = makeInitializedManager(target = Rect(0.1f, 0.1f, 0.2f, 0.2f))
        val active1 = manager.getAllBuffers()

        manager.registerTarget(Rect(0.11f, 0.11f, 0.2f, 0.2f), 1.1, config)
        val active2 = manager.getAllBuffers()

        assertEquals(1, active1.size)
        assertEquals(1, active2.size)
        assertEquals(active1[0].id, active2[0].id)
    }

    @Test
    fun `registerTarget creates distinct buffers for distinct targets`() = runTest {
        val (manager, config) = makeInitializedManager(target = Rect(0.1f, 0.1f, 0.1f, 0.1f))
        manager.registerTarget(Rect(0.8f, 0.8f, 0.1f, 0.1f), 1.0, config)

        val active = manager.getAllBuffers()
        assertEquals(2, active.size)
        assertNotEquals(active[0].id, active[1].id)
    }

    // Appending & Polling

    @Test
    fun `append accumulates frames and flush poll returns all of them`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect.ZERO)
        val active = manager.getAllBuffers()
        val id = active[0].id

        repeat(10) { i -> manager.append(id, createDummyUnit(), createDummyContext(1.0 + i)) }

        val cmd = manager.poll(InferenceMode.STREAM, flush = true)
        assertNotNull(cmd)
        assertEquals(10u, cmd?.takeCount)
    }

    @Test
    fun `append to an unknown buffer is a no-op`() = runTest {
        val (manager, _) = makeInitializedManager()

        manager.append("ghost_id_123", createDummyUnit(), createDummyContext(1.0))

        val cmd = manager.poll(InferenceMode.STREAM, flush = true)
        assertNull(cmd)
    }

    @Test
    fun `poll returns null when there are insufficient frames`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect.ZERO)
        val active = manager.getAllBuffers()
        val id = active[0].id

        repeat(5) { i -> manager.append(id, createDummyUnit(), createDummyContext(1.0 + i)) }

        val cmd = manager.poll(InferenceMode.STREAM)
        assertNull(cmd)
    }

    @Test
    fun `poll returns a command once there are sufficient frames`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect.ZERO)
        val active = manager.getAllBuffers()
        val id = active[0].id

        repeat(15) { i -> manager.append(id, createDummyUnit(), createDummyContext(1.0 + i)) }

        val cmd = manager.poll(InferenceMode.STREAM)
        assertNotNull(cmd)
        assertEquals(id, cmd?.bufferId)
        assertEquals(15u, cmd?.takeCount)
    }

    @Test
    fun `poll drops stale buffers`() = runTest {
        val (manager, config) = makeInitializedManager(target = Rect(0.1f, 0.1f, 0.1f, 0.1f))
        val active1 = manager.getAllBuffers()
        val id1 = active1[0].id

        manager.registerTarget(Rect(0.8f, 0.8f, 0.1f, 0.1f), 10.0, config)
        val active2 = manager.getAllBuffers()
        val id2 = active2.first { it.id != id1 }.id

        repeat(15) { i -> manager.append(id2, createDummyUnit(), createDummyContext(10.0 + i)) }

        val cmd = manager.poll(InferenceMode.STREAM)
        assertEquals(id2, cmd?.bufferId)

        val flushCmd = InferenceCommand(id1, 1u, 0u)
        val executed = manager.execute(flushCmd)
        assertNull(executed)
    }

    // Execution

    @Test
    fun `execute extracts frames across successive calls`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect.ZERO)
        val active = manager.getAllBuffers()
        val id = active[0].id

        repeat(12) { i -> manager.append(id, createDummyUnit(), createDummyContext(1.0 + i)) }

        val payload = manager.execute(InferenceCommand(id, 10u, 3u))
        assertNotNull(payload)
        assertEquals(10, payload?.size)

        val payload2 = manager.execute(InferenceCommand(id, 5u, 0u))
        assertEquals(5, payload2?.size)
    }

    // State & Lifecycle

    @Test
    fun `state management stores and overwrites the current state`() = runTest {
        val (manager, _) = makeInitializedManager()

        assertNull(manager.getState())

        manager.updateState(MockState("state_1"))
        assertEquals("state_1", (manager.getState() as? MockState)?.id)

        manager.updateState(MockState("state_2"))
        assertEquals("state_2", (manager.getState() as? MockState)?.id)
    }

    @Test
    fun `reset clears buffers state and timestamp`() = runTest {
        val (manager, _) = makeInitializedManager(target = Rect.ZERO)
        val active = manager.getAllBuffers()
        val id = active[0].id
        manager.append(id, createDummyUnit(), createDummyContext(1.0))
        manager.updateState(MockState("test_state"))

        manager.reset()

        assertNull(manager.getState())
        assertTrue(manager.getAllBuffers().isEmpty())

        val cmd = manager.poll(InferenceMode.STREAM, flush = true)
        assertNull(cmd)
    }

    @Test
    fun `close releases the underlying native buffer planner`() = runTest {
        val (manager, _) = makeInitializedManager()
        manager.close()
        // No planner left to poll against, so this should simply return null,
        // not throw.
        assertNull(manager.poll(InferenceMode.STREAM))
    }
}
