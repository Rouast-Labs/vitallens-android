package com.rouast.vitallens.inference.buffer

import com.rouast.vitallens.core.InferenceCommand
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.network.ModelConfig

/**
 * A container that accumulates processed video frames for a specific region of interest (ROI)
 * over time. Manages an internal sliding window, automatically dropping the oldest frames when
 * its maximum capacity is reached, and executes extraction commands issued by the buffer planner.
 *
 * Owned exclusively by [BufferManager], which is Mutex-guarded — callers never touch a
 * [FrameBuffer] concurrently, so this class itself needs no locking of its own.
 */
class FrameBuffer(
    val id: String,
    val roi: Rect,
    val mode: InferenceMode,
    private val config: ModelConfig,
    val createdAt: Double,
) {
    /** Mutable: [BufferManager] updates this directly when keeping a matched buffer alive. */
    var lastSeen: Double = createdAt

    private val buffer = mutableListOf<Pair<InferenceUnit, InferenceContext>>()

    private val maxCapacity: Int =
        if (mode == InferenceMode.FILE) 1000 else maxOf(150, (config.fpsTarget * 10).toInt())

    /** The current number of frames stored in the buffer. */
    val count: Int get() = buffer.size

    /**
     * Appends a new processed frame to the buffer.
     * Automatically discards the oldest frames if the internal capacity is exceeded.
     */
    fun append(unit: InferenceUnit, context: InferenceContext) {
        buffer.add(unit to context)
        lastSeen = context.timestamp

        val overflow = buffer.size - maxCapacity
        if (overflow > 0) buffer.subList(0, overflow).clear()
    }

    /**
     * Extracts a sequence of frames based on an inference command and adjusts the internal
     * sliding window.
     *
     * @return The frame units and their contexts, or `null` if the buffer doesn't have enough
     *   frames to satisfy the take count.
     */
    fun execute(command: InferenceCommand): List<Pair<InferenceUnit, InferenceContext>>? {
        val take = command.takeCount.toInt()
        val keep = command.keepCount.toInt()

        if (take <= 0 || buffer.size < take) return null

        val payload = buffer.subList(0, take).toList()
        val elementsToRemove = maxOf(0, take - keep)
        if (elementsToRemove > 0) buffer.subList(0, elementsToRemove).clear()

        return payload
    }
}
