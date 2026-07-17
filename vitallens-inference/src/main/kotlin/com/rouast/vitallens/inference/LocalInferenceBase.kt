package com.rouast.vitallens.inference

import android.graphics.Bitmap
import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.core.computeBufferConfig
import com.rouast.vitallens.inference.network.ModelConfig

/**
 * A generic base strategy for running local, on-device models. This SDK has no opinion on which
 * runtime you use (TFLite, ONNX Runtime, or anything else) — subclass this and implement
 * [predict] with your specific model's execution logic.
 */
abstract class LocalInferenceBase(
    val config: ModelConfig,
) : InferenceStrategy {

    /** The buffer configuration dictated by the active model settings. */
    override suspend fun bufferConfig(): BufferConfig = computeBufferConfig(config.toSessionConfig())

    /** Returns the locally predefined model configuration. */
    override suspend fun resolveConfig(): ModelConfig = config

    /**
     * Executes the actual local model prediction. Subclasses must implement this with their
     * specific on-device model execution logic.
     *
     * @param frames Prepared bitmaps ready for the model.
     * @param state The opaque recurrent state from the previous inference, if any.
     */
    abstract suspend fun predict(frames: List<Bitmap>, state: InferenceState?): InferenceOutcome

    /**
     * Processes a window of frames using the local prediction logic. Ensures all incoming
     * frames are in the expected [InferenceUnit.PixelBuffer] format before calling [predict].
     */
    override suspend fun infer(
        window: List<Pair<InferenceUnit, InferenceContext>>,
        state: InferenceState?,
        mode: InferenceMode,
        model: String?,
    ): InferenceOutcome {
        val frames = window.map { (unit, _) ->
            (unit as? InferenceUnit.PixelBuffer)?.bitmap
                ?: throw VitalLensException.ProcessingError("Local strategy requires .pixelBuffer inputs")
        }
        return predict(frames, state)
    }
}
