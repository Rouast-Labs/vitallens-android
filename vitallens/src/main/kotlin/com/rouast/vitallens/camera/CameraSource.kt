package com.rouast.vitallens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.rouast.vitallens.CameraStreaming
import com.rouast.vitallens.FrameImage
import com.rouast.vitallens.InputFrame
import com.rouast.vitallens.core.FramePlaneBuffer
import com.rouast.vitallens.core.YuvMatrix
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.VitalLensException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Whether [CameraSource.setSettingsLocked] locks the camera. Switched off: on a Samsung phone the
 * first version showed a green colour cast and low confidence, probably because auto white
 * balance was locked before it had converged. Re-enable only once the lock waits for convergence
 * (CONTROL_AWB_STATE / CONTROL_AE_STATE) and has been validated on devices.
 */
private const val CAMERA_LOCK_ENABLED = false

/** Frame hand-off buffer size between capture and whatever consumes [CameraSource.stream]. */
private const val FRAME_CHANNEL_CAPACITY = 8

/**
 * Requested analysis resolution. 4:3 keeps the sensor's full field of view. Frame preparation
 * only reads the face crop, so a higher resolution costs little and averages more pixels into
 * each model input pixel. CameraX picks the closest size the device supports.
 */
private val ANALYSIS_RESOLUTION = Size(1280, 960)

/**
 * Camera YUV_420_888 frames are full-range BT.601 (JFIF), as on iOS. The previous RGBA path
 * converted them as limited range.
 */
private val CAMERA_YUV_MATRIX = YuvMatrix.BT601_FULL

/** Wraps a CameraX YUV_420_888 image for in-place reading; closing the frame closes the image. */
private fun ImageProxy.toFrameImage(): FrameImage.Yuv420 {
    val (y, u, v) = planes
    return FrameImage.Yuv420(
        y = FramePlaneBuffer(y.buffer, y.rowStride, y.pixelStride),
        u = FramePlaneBuffer(u.buffer, u.rowStride, u.pixelStride),
        v = FramePlaneBuffer(v.buffer, v.rowStride, v.pixelStride),
        planesWidth = width,
        planesHeight = height,
        visible = cropRect,
        matrix = CAMERA_YUV_MATRIX,
        onClose = ::close,
    )
}

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
 * Frames are CameraX's YUV_420_888 images, read in place by vitallens-core: nothing is converted
 * or copied per frame. Each [InputFrame] holds its camera image until it is closed, and CameraX
 * delivers no new frame meanwhile, so consumers must close frames promptly (StreamProcessor
 * does).
 *
 * Camera permission is only checked, never requested: Android has no API for library code to
 * prompt for a runtime permission without an `Activity` — the host app must request
 * [Manifest.permission.CAMERA] itself before calling [start].
 */
class CameraSource(private val context: Context) : CameraStreaming {

    /** Requested analysis resolution; settable for tests. */
    internal var analysisResolution: Size = ANALYSIS_RESOLUTION


    // An explicit capacity, not Channel.BUFFERED: combined with a non-SUSPEND onBufferOverflow,
    // Channel.BUFFERED collapses to a hardcoded capacity of 1 (a ConflatedBufferedChannel) —
    // confirmed via decompiling kotlinx-coroutines-core's Channel() factory — which would silently
    // keep only the single latest frame rather than smoothing over brief consumer backpressure.
    private val channel = Channel<InputFrame>(
        capacity = FRAME_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        // Frames dropped or never received still hold a camera image
        onUndeliveredElement = { it.close() },
    )
    override val stream: Flow<InputFrame> = channel.receiveAsFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var pendingPreviewView: PreviewView? = null
    private var boundCamera: Camera? = null
    private var analysisExecutor: ExecutorService? = null

    override suspend fun start() {
        if (cameraProvider != null) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw VitalLensException.ProcessingError("Camera access denied")
        }

        val provider = getCameraProvider()

        // TODO: Camera control for rPPG stability (auto AE/AWB shifts are global brightness/colour
        //   changes far larger than the pulse signal, and also trigger false light_quality dips).
        //   Exposure and white balance are locked once a face is in view (see setSettingsLocked and
        //   StreamProcessor). Still open, best effort (device support varies):
        //   - Camera2Interop.Extender on the ImageAnalysis/Preview builders:
        //     CONTROL_AE_TARGET_FPS_RANGE = (30, 30) (or (15, 15) for eco mode), and fix focus, e.g.
        //     CONTROL_AF_MODE_OFF at current distance.
        //   - If ROI brightness drifts beyond a threshold, unlock, re-converge, re-lock.
        //   - Never derive exposure/gains from skin-tone colour statistics (patent EP4353143A1).
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(analysisResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .build()
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        analysis.setAnalyzer(executor) { image -> onFrame(image) }

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
            boundCamera = provider.bindToLifecycle(ProcessLifecycleOwner.get(), CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }
        cameraProvider = provider
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    override fun stop() {
        // Capture request options (such as the AE and AWB locks) stay on the camera after it is
        // unbound, so a later session would start with them still set: clear them explicitly.
        boundCamera?.let { camera ->
            runCatching { Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions() }
        }
        val provider = cameraProvider
        cameraProvider = null
        previewUseCase = null
        boundCamera = null
        channel.close()
        // Fire-and-forget onto the main thread: unbindAll() must run on the main thread, but
        // stop() itself isn't suspend (matching CameraStreaming's synchronous contract), so this
        // can't await completion, only dispatch it.
        val executor = analysisExecutor
        analysisExecutor = null
        provider?.let {
            ContextCompat.getMainExecutor(context).execute {
                it.unbindAll()
                executor?.shutdown()
            }
        } ?: executor?.shutdown()
    }

    /**
     * Keeps the camera's current exposure and white balance, or returns to automatic.
     *
     * Uses the Camera2 auto-exposure and auto-white-balance locks, only where the device reports
     * that they are available; anything else is left alone. Values are never derived from the image
     * content: the camera keeps the ones it converged on.
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    override fun setSettingsLocked(locked: Boolean) {
        if (!CAMERA_LOCK_ENABLED) return
        val camera = boundCamera ?: return
        runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val options = CaptureRequestOptions.Builder()
            if (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            }
            if (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
            }
            Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options.build())
        }
    }

    override fun showPreview(view: PreviewView) {
        pendingPreviewView = view
        previewUseCase?.surfaceProvider = view.surfaceProvider
    }

    private fun onFrame(image: ImageProxy) {
        val frame = try {
            InputFrame(
                image = image.toFrameImage(),
                orientation = rotationDegreesToOrientation(image.imageInfo.rotationDegrees),
                isMirrored = true,
                timestamp = image.imageInfo.timestamp / 1_000_000_000.0,
            )
        } catch (e: Exception) {
            image.close()
            return
        }
        if (!channel.trySend(frame).isSuccess) frame.close()
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
