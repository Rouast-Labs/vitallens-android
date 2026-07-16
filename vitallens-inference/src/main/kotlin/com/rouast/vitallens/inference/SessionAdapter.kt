package com.rouast.vitallens.inference

import com.rouast.vitallens.core.FaceInput
import com.rouast.vitallens.core.SessionConfig
import com.rouast.vitallens.core.SessionInput
import com.rouast.vitallens.core.SessionResult
import com.rouast.vitallens.core.SignalInput
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.StateData
import com.rouast.vitallens.inference.model.Vital
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.Waveform
import com.rouast.vitallens.inference.network.ModelConfig
import com.rouast.vitallens.core.Rect as CoreRect

/** Converts this [ModelConfig] into a [SessionConfig] required by the Core engine. */
fun ModelConfig.toSessionConfig(): SessionConfig = SessionConfig(
    modelName = modelName,
    supportedVitals = supportedVitals,
    returnWaveforms = listOf("ppg_waveform", "respiratory_waveform"),
    fpsTarget = fpsTarget.toFloat(),
    inputSize = inputSize.toULong(),
    nInputs = nInputs.toULong(),
    roiMethod = roiMethod,
    estimateRollingVitals = null,
)

/** Converts this [Rect] into the generated Core [CoreRect]. */
fun Rect.toRustRect(): CoreRect = CoreRect(x = x, y = y, width = width, height = height)

/** Converts this [VitalLensResult] into a [SessionInput] to be fed into the Core engine. */
fun VitalLensResult.toSessionInput(): SessionInput {
    val signalsMap = waveforms.mapValues { (_, wave) ->
        SignalInput(data = wave.data, confidence = wave.confidence)
    }

    val coords = face.coordinates
    val confs = face.confidence
    val faceInput = if (coords != null && confs != null) {
        FaceInput(
            coordinates = coords.map { row -> row.map { it.toFloat() } },
            confidence = confs.map { it.toFloat() },
        )
    } else {
        null
    }

    return SessionInput(face = faceInput, signals = signalsMap, timestamp = time)
}

/**
 * Converts this [SessionResult] from the Core engine back into a high-level [VitalLensResult].
 *
 * @param originalState The opaque state data to attach to the final result.
 * @param message An optional message overriding the result's own [SessionResult.message].
 * @param modelUsed The identifier of the model used to generate this data.
 */
fun SessionResult.toVitalLensResult(
    originalState: StateData?,
    message: String?,
    modelUsed: String?,
): VitalLensResult {
    val finalWaveforms = waveforms.mapValues { (_, wave) ->
        Waveform(data = wave.data, confidence = wave.confidence, unit = wave.unit, note = wave.note)
    }

    val finalRollingVitals = rollingVitals?.takeIf { it.isNotEmpty() }?.mapValues { (_, wave) ->
        Waveform(data = wave.data, confidence = wave.confidence, unit = wave.unit, note = wave.note)
    }

    val finalVitals = vitals.mapValues { (_, vital) ->
        Vital(
            value = vital.value.toDouble(),
            confidence = vital.confidence.toDouble(),
            unit = vital.unit,
            note = vital.note,
        )
    }

    val faceData = face?.let {
        FaceData(
            coordinates = it.coordinates.map { row -> row.map { v -> v.toDouble() } },
            confidence = it.confidence.map { v -> v.toDouble() },
            note = it.note,
        )
    } ?: FaceData(coordinates = null, confidence = null, note = null)

    return VitalLensResult(
        face = faceData,
        vitals = finalVitals,
        waveforms = finalWaveforms,
        time = timestamp,
        fps = fps.toDouble(),
        modelUsed = modelUsed,
        state = originalState,
        message = message ?: this.message,
        sampleCount = timestamp.size,
        rollingVitals = finalRollingVitals,
    )
}
