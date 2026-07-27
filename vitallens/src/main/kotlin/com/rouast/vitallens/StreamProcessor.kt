package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.core.Session
import com.rouast.vitallens.core.WaveformMode
import com.rouast.vitallens.image.ImageProcessor
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.buffer.BufferManager
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.inference.toSessionConfig
import com.rouast.vitallens.inference.toSessionInput
import com.rouast.vitallens.inference.toVitalLensResult
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.roi.FaceROIStrategy
import com.rouast.vitallens.roi.ROIStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import kotlin.math.pow

/**
 * Converts a raw camera frame into an [InferenceUnit], given the frame's region of interest,
 * model configuration, and orientation metadata.
 */
typealias FrameTransformer = (Bitmap, Rect, ModelConfig, ImageOrientation, Boolean) -> InferenceUnit

private val defaultFrameTransformer: FrameTransformer = { bitmap, roi, config, orientation, isMirrored ->
    val processed = ImageProcessor.process(bitmap, roi, config.inputSize, orientation, isMirrored)
    InferenceUnit.RgbData(ImageProcessor.toRgbBytes(processed))
}

/**
 * The core engine that coordinates the camera, ROI tracking, buffering, and the background
 * inference loop.
 *
 * Mirrors Swift's `StreamProcessor` actor: serialized state access becomes an explicit [Mutex],
 * kept to short, non-suspending critical sections that match where the Swift actor implicitly
 * releases exclusivity at each `await` — [Mutex] does not do this automatically, so holding it
 * across a suspend call (e.g. a network-backed [InferenceStrategy.infer]) would block
 * [processFrame] for the call's entire duration. The background inference/frame-forwarding
 * `Task`s become [scope]-owned jobs. `debugMode`/`defaultImageProcessor`/`debugImage` are
 * dropped, consistent with `ImageProcessor.kt` having already dropped the same on that side.
 *
 * [camera] has no default (unlike Swift's `camera ?? CameraSource()`): `CameraSource` requires an
 * Android `Context` to construct, which this class has no business owning — callers construct it
 * and pass it in.
 */
class StreamProcessor(
    private val strategy: InferenceStrategy,
    private val camera: CameraStreaming,
    roiStrategy: ROIStrategy? = null,
    transformer: FrameTransformer? = null,
    private val waveformMode: WaveformMode = WaveformMode.Incremental,
) : Closeable {

    private val roiStrategy: ROIStrategy = roiStrategy ?: FaceROIStrategy()
    private val transformer: FrameTransformer = transformer ?: defaultFrameTransformer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val bufferManager = BufferManager()

    private val outputFlow = MutableSharedFlow<VitalLensResult>(extraBufferCapacity = 64)

    private var config: ModelConfig? = null
    private var session: Session? = null
    private var lastFacePresence: Boolean = false
    private var onFaceStateChanged: ((Boolean) -> Unit)? = null
    private var lastProcessedTime: Double = -1.0
    private var streamGeneration: Int = 0

    /**
     * Read from [processFrame] on every frame and written from [pause]/[resume]/[stop] — kept
     * outside [mutex] as a simple visibility-only flag rather than adding suspend-only gating to
     * hot per-frame code and to [stop], which mirrors Swift's synchronous (non-`async`) signature.
     */
    @Volatile private var isPaused: Boolean = false

    private var frameSignalChannel: Channel<Unit>? = null
    private var inferenceJob: Job? = null
    private var cameraForwardingJob: Job? = null

    /** Starts the camera stream and the background inference loop. */
    suspend fun start(): SharedFlow<VitalLensResult> {
        val resolvedConfig = strategy.resolveConfig()
        val bufConfig = strategy.bufferConfig()
        bufferManager.initialize(bufConfig)

        mutex.withLock {
            session?.close()
            session = Session(resolvedConfig.toSessionConfig())
            config = resolvedConfig
        }
        isPaused = false

        val signalChannel = Channel<Unit>(Channel.CONFLATED)
        frameSignalChannel = signalChannel
        inferenceJob = scope.launch { runInferenceLoop(signalChannel.receiveAsFlow()) }

        camera.start()

        cameraForwardingJob = scope.launch {
            camera.stream.collect { frame ->
                if (!isPaused) processFrame(frame)
            }
        }

        return outputFlow.asSharedFlow()
    }

    /** Pauses the camera stream and prevents new frames from being processed. */
    suspend fun pause() {
        isPaused = true
        camera.stop()
    }

    /** Resumes the camera stream and frame processing. */
    suspend fun resume() {
        isPaused = false
        camera.start()
    }

    /**
     * Resets the internal buffers and the inference session state. Use this when the subject
     * changes abruptly.
     */
    suspend fun reset() {
        bufferManager.reset()
        mutex.withLock {
            session?.reset()
            streamGeneration += 1
        }
    }

    /**
     * Stops the camera stream, cancels background tasks, and clears frame buffers. Can be
     * followed by another [start] — for permanent teardown, use [close] instead.
     */
    fun stop() {
        isPaused = true
        camera.stop()

        inferenceJob?.cancel()
        cameraForwardingJob?.cancel()
        frameSignalChannel?.close()
        inferenceJob = null
        cameraForwardingJob = null
        frameSignalChannel = null

        scope.launch { bufferManager.reset() }
    }

    /** Sets a callback triggered immediately when face presence changes, or `null` to clear it. */
    suspend fun setFaceStateCallback(callback: ((Boolean) -> Unit)?) {
        mutex.withLock { onFaceStateChanged = callback }
    }

    /**
     * Processes an incoming video frame. Handles framerate targeting, ROI determination, and
     * buffer insertion.
     */
    suspend fun processFrame(frame: InputFrame) {
        if (isPaused) return
        val activeConfig = mutex.withLock { config } ?: return

        val shouldProcess = mutex.withLock {
            if (frame.timestamp < lastProcessedTime) lastProcessedTime = -1.0
            val minInterval = 1.0 / activeConfig.fpsTarget
            if (frame.timestamp - lastProcessedTime < minInterval - 0.005) {
                false
            } else {
                lastProcessedTime = frame.timestamp
                true
            }
        }
        if (!shouldProcess) return

        val target = roiStrategy.determineROI(frame.bitmap, frame.orientation, frame.isMirrored, activeConfig.roiMethod)

        val isFacePresent = target != null
        val callback = mutex.withLock {
            if (isFacePresent != lastFacePresence) {
                lastFacePresence = isFacePresent
                onFaceStateChanged
            } else {
                null
            }
        }
        callback?.invoke(isFacePresent)

        if (target == null) {
            bufferManager.reset()
            return
        }

        bufferManager.registerTarget(target, frame.timestamp, activeConfig)
        val allBuffers = bufferManager.getAllBuffers()
        if (allBuffers.isEmpty()) return

        for (item in allBuffers) {
            try {
                val unit = transformer(frame.bitmap, item.roi, activeConfig, frame.orientation, frame.isMirrored)
                val context = InferenceContext(
                    timestamp = frame.timestamp,
                    orientation = frame.orientation,
                    isMirrored = frame.isMirrored,
                    roi = item.roi,
                )
                bufferManager.append(item.id, unit, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silently ignore transformation errors for individual frames, matching Swift.
            }
        }

        frameSignalChannel?.trySend(Unit)
    }

    /**
     * The background task that continuously polls the buffer manager and triggers the inference
     * strategy when ready.
     */
    private suspend fun runInferenceLoop(signals: Flow<Unit>) {
        var consecutiveErrors = 0

        signals.collect {
            pollLoop@ while (currentCoroutineContext().isActive) {
                val command = bufferManager.poll(InferenceMode.STREAM) ?: break@pollLoop

                if (consecutiveErrors > 0) {
                    delay((2.0.pow(consecutiveErrors) * 100).toLong())
                }

                val window = bufferManager.execute(command) ?: break@pollLoop
                val currentState = bufferManager.getState()
                val currentGeneration = mutex.withLock { streamGeneration }
                val modelName = mutex.withLock { config?.modelName }

                try {
                    val outcome = strategy.infer(window, currentState, InferenceMode.STREAM, modelName)

                    if (mutex.withLock { currentGeneration != streamGeneration }) {
                        continue@pollLoop
                    }

                    consecutiveErrors = 0
                    bufferManager.updateState(outcome.newState)

                    val refined = mutex.withLock {
                        session?.let { sess ->
                            val sessionResult = sess.process(outcome.result.toSessionInput(), waveformMode)
                            sessionResult.toVitalLensResult(
                                originalState = outcome.result.state,
                                message = outcome.result.message,
                                modelUsed = outcome.result.modelUsed,
                            )
                        }
                    }
                    refined?.let { outputFlow.emit(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors >= 3) {
                        bufferManager.reset()
                        mutex.withLock {
                            session?.close()
                            session = config?.let { Session(it.toSessionConfig()) }
                        }
                        consecutiveErrors = 0
                    }
                }
            }
        }
    }

    /**
     * Permanently releases native resources. Kotlin-only — unlike Swift's ARC-driven `deinit`,
     * the JVM has no deterministic cleanup hook, so this is called explicitly once the processor
     * is no longer needed. Unlike [stop], this cannot be undone with another [start].
     */
    override fun close() {
        stop()
        scope.cancel()
        runBlocking { bufferManager.close() }
        session?.close()
        (roiStrategy as? Closeable)?.close()
    }
}
