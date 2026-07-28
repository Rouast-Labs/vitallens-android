package com.rouast.vitallens.inference

import android.graphics.Bitmap
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ModelConfig

/**
 * EXIF-style physical orientation of a source image. Framework-agnostic like [Rect]: consumed
 * here by [InferenceContext], and also by the camera/image processing layer (`vitallens` module)
 * rather than being redefined there.
 */
enum class ImageOrientation {
    UP,
    UP_MIRRORED,
    DOWN,
    DOWN_MIRRORED,
    LEFT_MIRRORED,
    RIGHT,
    RIGHT_MIRRORED,
    LEFT,
}

/** Represents the unit of image data to be processed during inference. */
sealed class InferenceUnit {
    /** Pre-processed, flattened RGB bytes. Typically used for the remote API. */
    class RgbData(val data: ByteArray) : InferenceUnit()

    /** A raw image buffer. Typically used for local on-device inference. */
    class PixelBuffer(val bitmap: Bitmap) : InferenceUnit()
}

/** Contextual metadata associated with a specific video frame unit. */
data class InferenceContext(
    /** The timestamp of the frame in seconds. */
    val timestamp: Double,
    /** The physical orientation of the original image. */
    val orientation: ImageOrientation = ImageOrientation.UP,
    /** Whether the original image is horizontally mirrored. */
    val isMirrored: Boolean = false,
    /** The normalized bounding box defining the region of interest within the frame. */
    val roi: Rect = Rect.ZERO,
)

/** Marker interface for the recurrent internal state of an inference model. */
interface InferenceState

/** The result of a single [InferenceStrategy.infer] pass: the estimated signals and the updated state. */
data class InferenceOutcome(val result: VitalLensResult, val newState: InferenceState?)

/** Defines an abstract backend strategy for estimating vital signs from video frames. */
interface InferenceStrategy {

    /** The buffering limits and parameters for this specific strategy. */
    suspend fun bufferConfig(): BufferConfig

    /** Resolves the model configuration (e.g., input size, target FPS) required by this strategy. */
    suspend fun resolveConfig(): ModelConfig

    /**
     * Processes a window of frames and returns the estimated time-series signals.
     *
     * @param window A list of frame units and their associated context metadata.
     * @param state The opaque recurrent state from the previous inference pass, if any.
     * @param mode The execution mode dictating how data is handled (live stream or batch file).
     * @param model An optional model identifier to override the default selection.
     */
    suspend fun infer(
        window: List<Pair<InferenceUnit, InferenceContext>>,
        state: InferenceState?,
        mode: InferenceMode,
        model: String?,
    ): InferenceOutcome
}
