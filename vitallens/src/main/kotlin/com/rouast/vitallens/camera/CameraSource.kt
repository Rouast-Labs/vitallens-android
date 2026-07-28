package com.rouast.vitallens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.rouast.vitallens.CameraStreaming
import com.rouast.vitallens.InputFrame
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.VitalLensException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Frame hand-off buffer size between capture and whatever consumes [CameraSource.stream]. */
private const val FRAME_CHANNEL_CAPACITY = 8

/**
 * Converts CameraX's clockwise rotation-to-upright degrees (0/90/180/270, from
 * [ImageProxy.getImageInfo]'s `rotationDegrees` — already accounting for sensor mounting and
 * current display rotation) into [ImageOrientation].
 */
internal fun rotationDegreesToOrientation(degrees: Int): ImageOrientation =
    when (((degrees % 360) + 360) % 360) {
        90 -> ImageOrientation.RIGHT
        180 -> ImageOrientation.DOWN
        270 -> ImageOrientation.LEFT
        else -> ImageOrientation.UP
    }

/**
 * A camera source that captures video frames using CameraX and provides a [Flow] of [InputFrame]
 * objects.
 *
 * Uses [ProcessLifecycleOwner] rather than requiring a caller-supplied
 * [androidx.lifecycle.LifecycleOwner], so callers only need [start]/[stop], not lifecycle wiring.
 * Requests RGBA_8888 output directly from CameraX ([ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888]),
 * so no manual YUV conversion is needed here — CameraX's default YUV_420_888 output would
 * otherwise require one.
 *
 * Camera permission is only checked, never requested: Android has no API for library code to
 * prompt for a runtime permission without an `Activity` — the host app must request
 * [Manifest.permission.CAMERA] itself before calling [start].
 */
class CameraSource(private val context: Context) : CameraStreaming {

    // An explicit capacity, not Channel.BUFFERED: combined with a non-SUSPEND onBufferOverflow,
    // Channel.BUFFERED collapses to a hardcoded capacity of 1 (a ConflatedBufferedChannel) —
    // confirmed via decompiling kotlinx-coroutines-core's Channel() factory — which would silently
    // keep only the single latest frame rather than smoothing over brief consumer backpressure.
    private val channel = Channel<InputFrame>(
        capacity = FRAME_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val stream: Flow<InputFrame> = channel.receiveAsFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var pendingPreviewView: PreviewView? = null

    override suspend fun start() {
        if (cameraProvider != null) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw VitalLensException.ProcessingError("Camera access denied")
        }

        val provider = getCameraProvider()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image -> onFrame(image) }

        val preview = Preview.Builder().build()
        pendingPreviewView?.let { preview.surfaceProvider = it.surfaceProvider }
        previewUseCase = preview

        // CameraX's bind/unbind calls require the main thread; start() itself is callable from
        // any dispatcher (that's the point of it being suspend), so this must hop explicitly
        // rather than assume the caller is already there. Caught by an instrumented test on a
        // real emulator (IllegalStateException: Not in application's main thread) — this can't
        // be verified on a plain JVM unit test at all.
        withContext(Dispatchers.Main) {
            provider.unbindAll()
            provider.bindToLifecycle(ProcessLifecycleOwner.get(), CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }
        cameraProvider = provider
    }

    override fun stop() {
        val provider = cameraProvider
        cameraProvider = null
        previewUseCase = null
        channel.close()
        // Fire-and-forget onto the main thread: unbindAll() must run on the main thread, but
        // stop() itself isn't suspend (matching CameraStreaming's synchronous contract), so this
        // can't await completion, only dispatch it.
        provider?.let { ContextCompat.getMainExecutor(context).execute { it.unbindAll() } }
    }

    override fun showPreview(view: PreviewView) {
        pendingPreviewView = view
        previewUseCase?.surfaceProvider = view.surfaceProvider
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val frame = InputFrame(
                bitmap = image.toBitmap(),
                orientation = rotationDegreesToOrientation(image.imageInfo.rotationDegrees),
                isMirrored = true,
                timestamp = image.imageInfo.timestamp / 1_000_000_000.0,
            )
            channel.trySend(frame)
        } finally {
            image.close()
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
}
