package com.rouast.vitallens.inference.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Encapsulates the opaque recurrent state required for continuity when performing sequential
 * streaming inference. Clients must persist this and include it in the subsequent request.
 */
@Serializable(with = StateDataSerializer::class)
data class StateData(
    /** The base64-encoded string representing the flattened state tensors. */
    val data: String,
    val note: String?,
)

/**
 * The API may return `data` as either a base64 string or a raw `[Float]` array; the latter is
 * re-encoded as base64 of its little-endian byte representation, matching the layout Apple
 * platforms produce from `Data(buffer:)` over a `[Float]`.
 */
object StateDataSerializer : KSerializer<StateData> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StateData")

    override fun deserialize(decoder: Decoder): StateData {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("StateData can only be decoded from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val note = obj["note"]?.jsonPrimitive?.contentOrNull

        val dataElement = obj["data"]
            ?: throw SerializationException("State data field 'data' is required")

        val data = when {
            dataElement is JsonPrimitive && dataElement.isString -> dataElement.content
            dataElement is JsonArray -> {
                val floats = dataElement.map { it.jsonPrimitive.float }
                val buffer = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                floats.forEach { buffer.putFloat(it) }
                Base64.getEncoder().encodeToString(buffer.array())
            }
            else -> throw SerializationException(
                "State data expected to be String or [Float], got $dataElement",
            )
        }

        return StateData(data = data, note = note)
    }

    override fun serialize(encoder: Encoder, value: StateData) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("StateData can only be encoded to JSON")
        output.encodeJsonElement(
            buildJsonObject {
                put("data", value.data)
                value.note?.let { put("note", it) }
            },
        )
    }
}
