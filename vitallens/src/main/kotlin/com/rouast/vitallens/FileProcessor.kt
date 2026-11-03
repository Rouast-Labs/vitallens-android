package com.rouast.vitallens

import android.content.Context
import android.net.Uri
import com.rouast.vitallens.camera.DecodedFrame
import com.rouast.vitallens.camera.FileSource
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.core.InferenceCommand
import com.rouast.vitallens.core.Session
import com.rouast.vitallens.core.SessionInput
import com.rouast.vitallens.core.WaveformMode
import com.rouast.vitallens.image.CoreFrames
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.ROICalculator
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.inference.buffer.FrameBuffer
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.inference.toSessionConfig
import com.rouast.vitallens.inference.toSessionInput
import com.rouast.vitallens.inference.toVitalLensResult
import com.rouast.vitallens.vision.FaceDetector
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import kotlin.math.hypot

private val Rect.midX: Float get() = x + width / 2f
private val Rect.midY: Float get() = y + height / 2f

/**
 * Handles the two-pass processing of video files for vital sign estimation.
 *
 * - Pass 1 (Scanning): Iterates through the video to detect faces and calculate a stable, global
 *   ROI.
 * - Pass 2 (Inference): Processes frames using the stable ROI and batches them for the inference
 *   strategy.
 *
 * Takes both [context] and [uri] rather than just a file path: [FileSource] needs an Android
 * `Context` to construct (to access `MediaExtractor`/`MediaCodec` and content resolution), not
 * just the file's location.
 */
class FileProcessor(
    private val context: Context,
    private val uri: Uri,
    private val detector: FaceDetecting = FaceDetector(),
) {

    /**
     * Executes the full processing pipeline on the video file.
     *
     * @param strategy The inference strategy used to estimate vital signs.
     * @param globalROI An optional fixed region of interest. If provided, skips the scanning
     *   pass.
     * @return The aggregated [VitalLensResult] containing the estimated vital signs and
     *   waveforms.
     * @throws VitalLensException if processing, face detection, or inference fails.
     */
    suspend fun process(strategy: InferenceStrategy, globalROI: Rect? = null): VitalLensResult {
        try {
            val config = strategy.resolveConfig()
            val bufConfig = strategy.bufferConfig()

            val finalRoi = globalROI ?: performScanningPass(config)

            return performInferencePass(finalRoi, config, bufConfig, strategy)
        } finally {
            (detector as? Closeable)?.close()
        }
    }

    /**
     * Scans the video to determine a stable "median face" and derives the overall region of
     * interest.
     *
     * @throws VitalLensException.ProcessingError if no face is detected in the video.
     */
    private suspend fun performScanningPass(config: ModelConfig): Rect {
        val source = FileSource.from(context, uri)
        val stride = maxOf(1, source.nominalFrameRate.toInt())
        val detections = mutableListOf<Rect>()

        // Only the frames that go to face detection are converted to a Bitmap
        source.mapFrames { frame, index -> if ((index + 1) % stride == 0) frame.toBitmap() else null }.collect { frame ->
            val rect = runCatching { detector.detectFace(frame, source.orientation, isMirrored = false) }.getOrNull()
            if (rect != null) detections.add(rect)
        }

        if (detections.isEmpty()) {
            throw VitalLensException.ProcessingError("No face detected in video file.")
        }

        val centroidX = detections.sumOf { it.midX.toDouble() } / detections.size
        val centroidY = detections.sumOf { it.midY.toDouble() } / detections.size

        val bestFace = detections.minByOrNull { hypot(it.midX - centroidX, it.midY - centroidY) } ?: detections[0]

        return ROICalculator.calculateROI(bestFace, config.roiMethod)
    }

    /** Reads the video linearly, applies the fixed ROI, and performs batched inference. */
    private suspend fun performInferencePass(
        roi: Rect,
        config: ModelConfig,
        bufConfig: BufferConfig,
        strategy: InferenceStrategy,
    ): VitalLensResult {
        val source = FileSource.from(context, uri)
        val nominalFps = source.nominalFrameRate.toDouble()

        val buffer = FrameBuffer(id = "file", roi = roi, mode = InferenceMode.FILE, config = config, createdAt = 0.0)
        val session = Session(config.toSessionConfig(live = false))

        try {
            var currentState: InferenceState? = null
            var totalFramesProcessed = 0

            // The decoder's planes go straight to vitallens-core, which reads only the ROI
            source.mapFrames { frame, _ ->
                PreparedFrame(runCatching { prepareFrame(frame, roi, config.inputSize, source.orientation) }.getOrNull())
            }.collect { prepared ->
                val timestamp = totalFramesProcessed / nominalFps
                val frameContext = InferenceContext(
                    timestamp = timestamp,
                    orientation = source.orientation,
                    isMirrored = false,
                    roi = roi,
                )

                prepared.rgb?.let { bytes ->
                    buffer.append(InferenceUnit.RgbData(bytes), frameContext)
                }

                totalFramesProcessed++

                if (bufConfig.fileMax > 0u && buffer.count >= bufConfig.fileMax.toInt()) {
                    val command = InferenceCommand("file", bufConfig.fileMax, bufConfig.overlap)
                    val window = buffer.execute(command)
                    if (window != null) {
                        val outcome = strategy.infer(window, currentState, InferenceMode.FILE, config.modelName)
                        currentState = outcome.newState
                        session.process(outcome.result.toSessionInput(), WaveformMode.Incremental)
                    }
                }
            }

            var finalMessage: String? = null
            var finalModelUsed: String? = null

            if (buffer.count >= config.nInputs) {
                val command = InferenceCommand("file", buffer.count.toUInt(), 0u)
                val window = buffer.execute(command)
                if (window != null) {
                    val outcome = strategy.infer(window, currentState, InferenceMode.FILE, config.modelName)
                    session.process(outcome.result.toSessionInput(), WaveformMode.Incremental)
                    finalMessage = outcome.result.message
                    finalModelUsed = outcome.result.modelUsed
                }
            }

            val emptyInput = SessionInput(face = null, signals = emptyMap(), timestamp = emptyList())
            val globalResult = session.process(emptyInput, WaveformMode.Global)
            return globalResult.toVitalLensResult(originalState = null, message = finalMessage, modelUsed = finalModelUsed)
        } finally {
            session.close()
        }
    }
}

/** Model input for one decoded frame, or `null` if it could not be prepared. */
private class PreparedFrame(val rgb: ByteArray?)

/** Crops, converts and downsamples one decoded frame to model input with vitallens-core. */
private fun prepareFrame(frame: DecodedFrame, roi: Rect, targetSize: Int, orientation: ImageOrientation): ByteArray =
    when (frame) {
        is DecodedFrame.FromImage ->
            CoreFrames.fromImage(frame.image, roi, targetSize, orientation, isMirrored = false, matrix = frame.yuvMatrix)
        is DecodedFrame.FromBuffer -> CoreFrames.fromYuvBuffer(
            buffer = frame.buffer,
            offset = frame.offset,
            width = frame.width,
            height = frame.height,
            stride = frame.rowStride,
            sliceHeight = frame.ySliceHeight,
            semiPlanar = frame.semiPlanar,
            roi = roi,
            targetSize = targetSize,
            orientation = orientation,
            isMirrored = false,
            matrix = frame.yuvMatrix,
        )
    }
