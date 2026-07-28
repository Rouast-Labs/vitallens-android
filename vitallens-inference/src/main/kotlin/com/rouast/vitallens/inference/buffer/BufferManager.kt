package com.rouast.vitallens.inference.buffer

import com.rouast.vitallens.core.BufferActionType
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.BufferMetadata
import com.rouast.vitallens.core.BufferPlanner
import com.rouast.vitallens.core.InferenceCommand
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.inference.toRustRect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Information about a currently active buffer. */
data class ManagedBufferInfo(val id: String, val roi: Rect)

/**
 * Manages video frame buffers and coordinates with the core buffer planner. Handles the
 * lifecycles of multiple overlapping regions of interest, determining when frames should be
 * accumulated, dropped, or sent for inference.
 *
 * All mutating methods are `suspend fun`s guarded by a single [Mutex], since frames can arrive
 * concurrently with buffer-lifecycle changes (e.g. a new ROI being added mid-stream) and the
 * buffer planner's state must stay consistent across both.
 *
 * [close] exists because [BufferPlanner] holds a native Rust pointer (`Disposable`/
 * `AutoCloseable`) and the JVM has no deterministic destructor — callers must release it
 * explicitly rather than relying on garbage collection.
 */
class BufferManager {
    private val mutex = Mutex()

    private var bufferPlanner: BufferPlanner? = null
    private val buffers = mutableMapOf<String, FrameBuffer>()
    private var state: InferenceState? = null

    private var currentTimestamp: Double = 0.0

    /** Initializes the underlying buffer planner with the provided configuration. */
    suspend fun initialize(bufferConfig: BufferConfig): Unit = mutex.withLock {
        bufferPlanner = BufferPlanner(bufferConfig)
    }

    private fun getActiveMetadata(): List<BufferMetadata> = buffers.values.map { buf ->
        BufferMetadata(
            id = buf.id,
            roi = buf.roi.toRustRect(),
            count = buf.count.toUInt(),
            createdAt = buf.createdAt,
            lastSeen = buf.lastSeen,
        )
    }

    /**
     * Evaluates a newly detected target against the active buffers to decide whether to create
     * a new tracking buffer, keep an existing one alive, or ignore the target.
     */
    suspend fun registerTarget(target: Rect?, timestamp: Double, config: ModelConfig): Unit = mutex.withLock {
        val planner = bufferPlanner ?: return@withLock
        if (target == null) return@withLock
        currentTimestamp = maxOf(currentTimestamp, timestamp)

        val action = planner.evaluateTarget(target.toRustRect(), timestamp, getActiveMetadata())

        when (action.action) {
            BufferActionType.CREATE -> {
                val rustRoi = action.roi
                val rect = if (rustRoi != null) {
                    Rect(x = rustRoi.x, y = rustRoi.y, width = rustRoi.width, height = rustRoi.height)
                } else {
                    target
                }
                val newId = UUID.randomUUID().toString()
                buffers[newId] = FrameBuffer(
                    id = newId,
                    roi = rect,
                    mode = InferenceMode.STREAM,
                    config = config,
                    createdAt = timestamp,
                )
            }
            BufferActionType.KEEP_ALIVE -> {
                val matchedId = action.matchedId
                if (matchedId != null) {
                    buffers[matchedId]?.lastSeen = timestamp
                }
            }
            BufferActionType.IGNORE -> Unit
        }
    }

    /** Retrieves a snapshot of all currently active buffers. */
    suspend fun getAllBuffers(): List<ManagedBufferInfo> = mutex.withLock {
        buffers.map { (id, buf) -> ManagedBufferInfo(id = id, roi = buf.roi) }
    }

    /** Appends a new processed frame unit to the specified buffer. */
    suspend fun append(bufferId: String, unit: InferenceUnit, context: InferenceContext): Unit = mutex.withLock {
        currentTimestamp = maxOf(currentTimestamp, context.timestamp)
        buffers[bufferId]?.append(unit, context)
    }

    /**
     * Polls the planner to check if any buffer has accumulated enough frames to trigger
     * inference. Also drops stale buffers that have not received frames recently.
     *
     * @param flush If true, forces the planner to yield a command even if the buffer isn't full.
     * @return An [InferenceCommand] if a buffer is ready, or `null` if more frames are needed.
     */
    suspend fun poll(mode: InferenceMode, flush: Boolean = false): InferenceCommand? = mutex.withLock {
        val planner = bufferPlanner ?: return@withLock null

        val plan = planner.poll(getActiveMetadata(), currentTimestamp, mode, state != null, flush)

        for (id in plan.buffersToDrop) {
            buffers.remove(id)
        }

        plan.command
    }

    /**
     * Executes an inference command on a specific buffer, extracting the requested sequence of
     * frames.
     */
    suspend fun execute(command: InferenceCommand): List<Pair<InferenceUnit, InferenceContext>>? =
        mutex.withLock { buffers[command.bufferId]?.execute(command) }

    /** Updates the global inference state. */
    suspend fun updateState(newState: InferenceState?): Unit = mutex.withLock { state = newState }

    /** Retrieves the current global inference state. */
    suspend fun getState(): InferenceState? = mutex.withLock { state }

    /** Clears all active buffers, resets the internal timestamp, and nullifies the current state. */
    suspend fun reset(): Unit = mutex.withLock {
        buffers.clear()
        state = null
        currentTimestamp = 0.0
    }

    /** Closes the underlying native buffer planner, releasing its Rust-side resources. */
    suspend fun close(): Unit = mutex.withLock {
        bufferPlanner?.close()
        bufferPlanner = null
    }
}
