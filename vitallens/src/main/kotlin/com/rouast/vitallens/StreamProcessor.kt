package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.core.Session
import com.rouast.vitallens.core.WaveformMode
import com.rouast.vitallens.image.ImageProcessor
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.ConnectionMonitor
import com.rouast.vitallens.inference.ConnectionThresholds
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
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
 * State that multiple coroutines can touch concurrently is guarded by an explicit [Mutex], held
 * only across short, non-suspending critical sections: holding it across a suspend call (e.g. a
 * network-backed [InferenceStrategy.infer]) would block every other caller — including
 * [processFrame] on the hot per-frame path — for that call's entire duration, so any suspending
 * work happens outside the lock. The background inference/frame-forwarding loops are jobs owned
 * by [scope].
 *
 * [camera] has no default: `CameraSource` requires an Android `Context` to construct, which this
 * class has no business owning — callers construct it and pass it in.
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
    private var cameraLocked = false
    private var cameraLockJob: Job? = null
    private val cameraLockState = Any()

    /** How long (ms) a face must be in view before the camera's exposure and white balance are locked. */
    internal var cameraLockDelayMs: Long = 1_500

    private var onSlowConnection: (() -> Unit)? = null
    private var onConnectionUnstable: (() -> Unit)? = null
    private val connectionMonitor = ConnectionMonitor()
    private var unstableReported = false

    /** Frames in a single request above which the stream is reset as unstable. */
    internal var streamResetFrames: Int = ConnectionThresholds.STREAM_RESET_FRAMES
    private var lastProcessedTime: Double = -1.0
    private var streamGeneration: Int = 0

    /**
     * Read from [processFrame] on every frame and written from [pause]/[resume]/[stop] — kept
     * outside [mutex] as a simple visibility-only flag rather than adding suspend-only gating to
     * hot per-frame code, and because [stop] itself is a plain, non-suspending function that
     * callers can invoke synchronously (e.g. from cleanup code with no coroutine scope at hand).
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
            // A restarted stream starts without a face, so the face callback and the camera lock begin afresh
            lastFacePresence = false
        }
        isPaused = false
        connectionMonitor.reset()
        unstableReported = false

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
        resetCameraLock()
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
        resetCameraLock()
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
     * Sets a callback triggered once when the connection is too slow for the current frame rate,
     * or `null` to clear it. Only reported above the eco frame rate. The caller is expected to
     * restart the stream at a lower frame rate.
     */
    suspend fun setSlowConnectionCallback(callback: (() -> Unit)?) {
        mutex.withLock { onSlowConnection = callback }
    }

    /**
     * Sets a callback triggered once when the connection is so unstable that the stream cannot
     * continue, or `null` to clear it. This happens when too many frames queue up behind slow
     * requests, or when requests keep failing. Inference stops until the stream is restarted.
     */
    suspend fun setConnectionUnstableCallback(callback: (() -> Unit)?) {
        mutex.withLock { onConnectionUnstable = callback }
    }

    /**
     * Locks the camera's exposure and white balance once a face has been in view for the delay, and
     * releases them when the face is lost so the camera can adapt before the next lock.
     */
    private fun updateCameraLock(facePresent: Boolean) {
        if (facePresent) {
            synchronized(cameraLockState) {
                if (cameraLocked || cameraLockJob != null) return
                cameraLockJob = scope.launch {
                    delay(cameraLockDelayMs)
                    lockCameraNow()
                }
            }
        } else {
            val wasLocked = synchronized(cameraLockState) {
                cameraLockJob?.cancel()
                cameraLockJob = null
                val locked = cameraLocked
                cameraLocked = false
                locked
            }
            if (wasLocked) camera.setSettingsLocked(false)
        }
    }

    private fun lockCameraNow() {
        val shouldLock = synchronized(cameraLockState) {
            cameraLockJob = null
            if (cameraLocked) false else { cameraLocked = true; true }
        }
        if (shouldLock) camera.setSettingsLocked(true)
    }

    /**
     * Forgets a pending or held lock without touching the camera: used when the camera stops, as
     * a restarted camera starts without any lock.
     */
    private fun resetCameraLock() {
        synchronized(cameraLockState) {
            cameraLockJob?.cancel()
            cameraLockJob = null
            cameraLocked = false
        }
    }

    /** Stops inference after reporting an unstable connection, once. */
    private suspend fun reportUnstableConnection() {
        val callback = mutex.withLock {
            if (unstableReported) return
            unstableReported = true
            onConnectionUnstable
        }
        bufferManager.reset()
        callback?.invoke()
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
                updateCameraLock(isFacePresent)
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
                // A single bad frame (e.g. a transient decode failure) shouldn't take down the
                // whole stream — drop it and keep processing subsequent frames.
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
            if (mutex.withLock { unstableReported }) return@collect
            pollLoop@ while (currentCoroutineContext().isActive) {
                val command = bufferManager.poll(InferenceMode.STREAM) ?: break@pollLoop

                if (consecutiveErrors > 0) {
                    delay((2.0.pow(consecutiveErrors) * 100).toLong())
                }

                val window = bufferManager.execute(command) ?: break@pollLoop
                if (window.size > streamResetFrames) {
                    reportUnstableConnection()
                    break@pollLoop
                }
                val currentState = bufferManager.getState()
                val currentGeneration = mutex.withLock { streamGeneration }
                val modelName = mutex.withLock { config?.modelName }
                val requestStart = System.nanoTime()

                try {
                    val outcome = strategy.infer(window, currentState, InferenceMode.STREAM, modelName)

                    if (mutex.withLock { currentGeneration != streamGeneration }) {
                        continue@pollLoop
                    }

                    consecutiveErrors = 0

                    val fps = mutex.withLock { config?.fpsTarget }
                    if (fps != null && fps > ConnectionThresholds.ECO_FPS) {
                        val elapsedMs = (System.nanoTime() - requestStart) / 1_000_000.0
                        if (connectionMonitor.record(elapsedMs, window.size) != null) {
                            mutex.withLock { onSlowConnection }?.invoke()
                        }
                    }
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

                    // Requests that keep failing or timing out mean the connection is unusable
                    if (consecutiveErrors >= 2 && e is VitalLensException.NetworkError) {
                        reportUnstableConnection()
                        break@pollLoop
                    }

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
     * Permanently releases native resources. The JVM has no deterministic destructor, so this must
     * be called explicitly once the processor is no longer needed. Unlike [stop], this cannot be
     * undone with another [start].
     */
    override fun close() {
        stop()
        scope.cancel()
        runBlocking { bufferManager.close() }
        session?.close()
        (roiStrategy as? Closeable)?.close()
    }
}
