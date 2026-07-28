package com.rouast.vitallens

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rouast.vitallens.camera.CameraSource
import com.rouast.vitallens.core.WaveformMode
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ApiInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.Closeable

/**
 * The primary client for the VitalLens API and local inference.
 * Handles initialization, configuration, and stream lifecycle management.
 *
 * [context] is needed to construct the default [CameraSource] and to pass through to
 * [FileProcessor] for [processVideoFile]. [proxyUrl] is [HttpUrl] rather than a generic URL type,
 * matching [ApiInference]'s own parameter type — this codebase uses OkHttp throughout, not a
 * platform-agnostic URL abstraction.
 */
class VitalLens private constructor(
    private val context: Context,
    val apiKey: String?,
    val method: String,
    val faceDetectionFrequency: Double,
    val globalROI: Rect?,
    val proxyUrl: HttpUrl?,
    val overrideFps: Double?,
    val waveformMode: WaveformMode,
    private val camera: CameraStreaming?,
    private val strategy: InferenceStrategy,
    private val customTransformer: FrameTransformer?,
    initialProcessor: StreamProcessor?,
) : Closeable {

    /** Initializes a new VitalLens client. */
    constructor(
        context: Context,
        apiKey: String? = null,
        method: String = "vitallens",
        faceDetectionFrequency: Double = 1.0,
        globalROI: Rect? = null,
        proxyUrl: HttpUrl? = null,
        overrideFps: Double? = null,
        waveformMode: WaveformMode = WaveformMode.Incremental,
        source: CameraStreaming? = null,
        strategy: InferenceStrategy? = null,
        transformer: FrameTransformer? = null,
    ) : this(
        context = context,
        apiKey = apiKey,
        method = method,
        faceDetectionFrequency = faceDetectionFrequency,
        globalROI = globalROI,
        proxyUrl = proxyUrl,
        overrideFps = overrideFps,
        waveformMode = waveformMode,
        camera = source ?: CameraSource(context),
        strategy = strategy ?: ApiInference(
            apiKey = apiKey,
            proxyUrl = proxyUrl,
            requestedModel = if (method == "vitallens") null else method,
            overrideFps = overrideFps,
        ),
        customTransformer = transformer,
        initialProcessor = null,
    )

    /** Internal constructor for testing: bypasses normal construction, injecting a processor directly. */
    internal constructor(context: Context, processor: StreamProcessor) : this(
        context = context,
        apiKey = "test",
        method = "vitallens-2.0",
        faceDetectionFrequency = 0.5,
        globalROI = null,
        proxyUrl = null,
        overrideFps = null,
        waveformMode = WaveformMode.Incremental,
        camera = null,
        strategy = ApiInference(apiKey = "test"),
        customTransformer = null,
        initialProcessor = processor,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamProcessor: StreamProcessor? = initialProcessor

    /** A closure triggered instantly when a face enters or leaves the camera frame. */
    var onFaceStateChanged: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            scope.launch { streamProcessor?.setFaceStateCallback(value) }
        }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = handleAppBackground()
        override fun onStart(owner: LifecycleOwner) = handleAppForeground()
    }

    init {
        // Lifecycle.addObserver() is main-thread-confined; a caller may construct VitalLens from
        // any thread, so this can't just call ProcessLifecycleOwner.get() directly the way an
        // init{} block normally would (no suspend context available here to hop dispatchers).
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
    }

    private fun handleAppBackground() {
        scope.launch { streamProcessor?.pause() }
    }

    private fun handleAppForeground() {
        scope.launch { runCatching { streamProcessor?.resume() } }
    }

    /**
     * Starts the live camera stream and begins the inference loop.
     *
     * @param preview An optional view to render the live camera feed into.
     * @return A hot flow yielding continuous [VitalLensResult] updates. This flow has no
     *   "finished" signal — [stopStream] does not complete it, consumers manage their own
     *   collection lifecycle (matching [StreamProcessor.start]'s own `SharedFlow` semantics).
     */
    suspend fun startStream(preview: PreviewView? = null): SharedFlow<VitalLensResult> {
        val processor = streamProcessor ?: run {
            val resolvedCamera = camera
                ?: throw VitalLensException.ProcessingError("No camera source available")
            StreamProcessor(
                strategy = strategy,
                camera = resolvedCamera,
                transformer = customTransformer,
                waveformMode = waveformMode,
            ).also { streamProcessor = it }
        }

        processor.setFaceStateCallback(onFaceStateChanged)
        preview?.let { camera?.showPreview(it) }

        return processor.start()
    }

    /**
     * Resets the internal data buffers without stopping the camera.
     * Useful for forcing a new estimation window when the subject changes abruptly.
     */
    fun resetStream() {
        scope.launch { streamProcessor?.reset() }
    }

    /** Stops the active camera session, terminates the background inference loop, and clears internal buffers. */
    fun stopStream() {
        streamProcessor?.stop()
    }

    /**
     * Processes a local video file in batch mode.
     *
     * @param uri The local file [Uri] of the video to process.
     * @return A complete [VitalLensResult] containing the time-series estimates for the entire
     *   file.
     */
    suspend fun processVideoFile(uri: Uri): VitalLensResult {
        val processor = FileProcessor(context, uri)
        return processor.process(strategy, globalROI)
    }

    /**
     * Permanently releases resources: removes the lifecycle observer and closes the underlying
     * [StreamProcessor]. The JVM has no deterministic destructor, so callers must call this
     * explicitly once the client is no longer needed.
     */
    override fun close() {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        }
        scope.cancel()
        streamProcessor?.close()
    }
}
