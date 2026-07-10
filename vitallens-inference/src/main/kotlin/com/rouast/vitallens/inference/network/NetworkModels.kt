package com.rouast.vitallens.inference.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The response returned by the `/resolve-model` endpoint.
 * Contains the resolved model name and its specific configuration parameters.
 */
@Serializable
data class ResolveModelResponse(
    @SerialName("resolved_model") val resolvedModel: String,
    val config: ModelConfig,
)

/**
 * Configuration parameters for a specific VitalLens model.
 *
 * [modelName] is `@Transient`, mirroring the Swift original's CodingKeys
 * omission: it is never read from or written to JSON and always keeps its
 * default value after decoding.
 */
@Serializable
data class ModelConfig(
    @SerialName("n_inputs") val nInputs: Int,
    @SerialName("input_size") val inputSize: Int,
    @SerialName("fps_target") var fpsTarget: Double,
    @SerialName("roi_method") val roiMethod: String,
    @SerialName("supported_vitals") val supportedVitals: List<String>,
    @Transient var modelName: String = "vitallens",
)

/** Standard error response format returned by the API for 4xx and 5xx errors. */
@Serializable
internal data class APIErrorResponse(
    val message: String? = null,
)
