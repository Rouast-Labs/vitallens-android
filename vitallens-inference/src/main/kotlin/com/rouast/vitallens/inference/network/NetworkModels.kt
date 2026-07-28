package com.rouast.vitallens.inference.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlin.math.roundToInt

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
 * Decodes an [Int] that the API may serialize as a whole-number JSON float (e.g. `5.0`) rather
 * than an integer literal (observed against the real dev API for [ModelConfig.nInputs]/
 * [ModelConfig.inputSize]). kotlinx.serialization rejects that by default, so this tolerates a
 * whole-number float as an integer, but keeps genuinely fractional values (e.g. `5.5`) an error,
 * since that would indicate a real data problem rather than just an alternate whole-number
 * encoding.
 */
internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val value = input.decodeJsonElement()
        val double = (value as? JsonPrimitive)?.double
            ?: throw SerializationException("Expected a JSON number, got $value")
        val rounded = double.roundToInt()
        if (rounded.toDouble() != double) {
            throw SerializationException("Expected an integer value, got $double")
        }
        return rounded
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/**
 * Configuration parameters for a specific VitalLens model.
 *
 * [modelName] is `@Transient`: the API never sends it as part of a `config` object — it's set
 * separately by the caller after resolving which model to use (see [ApiInference]'s use of
 * [ResolveModelResponse.resolvedModel]) — so it must be excluded from (de)serialization entirely
 * rather than defaulting to `"vitallens"` on every decode.
 */
@Serializable
data class ModelConfig(
    @SerialName("n_inputs") @Serializable(with = LenientIntSerializer::class) val nInputs: Int,
    @SerialName("input_size") @Serializable(with = LenientIntSerializer::class) val inputSize: Int,
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
