package com.rouast.vitallens.inference.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Marker interface for auxiliary data attached to a result by the host application. */
interface ResultAuxiliaryData

/**
 * The comprehensive output from an inference strategy (remote API or local).
 * All physiological data is represented as time-series arrays or aggregated scalar values
 * corresponding to the processed video frames.
 */
@Serializable(with = VitalLensResultSerializer::class)
data class VitalLensResult(
    val face: FaceData,
    val vitals: Map<String, Vital>,
    val waveforms: Map<String, Waveform>,
    val time: List<Double>,
    val fps: Double? = null,
    val modelUsed: String? = null,
    val state: StateData? = null,
    val message: String? = null,
    val sampleCount: Int? = null,
    val auxiliary: ResultAuxiliaryData? = null,
    val rollingVitals: Map<String, Waveform>? = null,
) {
    val ppg: Waveform? get() = waveforms["ppg_waveform"]
    val resp: Waveform? get() = waveforms["respiratory_waveform"]
    val heartRate: Vital? get() = vitals["heart_rate"]
    val respiratoryRate: Vital? get() = vitals["respiratory_rate"]
    val hrvSdnn: Vital? get() = vitals["hrv_sdnn"]
    val hrvRmssd: Vital? get() = vitals["hrv_rmssd"]
    val sbp: Vital? get() = vitals["sbp"]
    val dbp: Vital? get() = vitals["dbp"]
    val spo2: Vital? get() = vitals["spo2"]
}

/**
 * Manual JSON (de)serialization mirroring the Swift source's dynamic-key handling:
 * - [VitalLensResult.time] is always empty on decode; it's synthesized elsewhere from frame
 *   timestamps and never read from the raw API response.
 * - `vitals`/`waveforms` entries are decoded individually and a malformed entry is skipped
 *   rather than failing the whole response; `rolling_vitals` is decoded strictly (all-or-nothing).
 * - `model_used`/`n` are the decode keys, but `modelUsed`/`sampleCount` (camelCase) are the
 *   encode keys — an asymmetry carried over verbatim from the Swift source, whose manual decode
 *   reads the API's snake_case wire format while its `Codable` conformance encodes using its own
 *   Swift-cased `CodingKeys`.
 */
object VitalLensResultSerializer : KSerializer<VitalLensResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("VitalLensResult")

    override fun deserialize(decoder: Decoder): VitalLensResult {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("VitalLensResult can only be decoded from JSON")
        val json = input.json
        val obj = input.decodeJsonElement().jsonObject

        val face = (obj["face"] as? JsonObject)?.let { json.decodeFromJsonElement(FaceData.serializer(), it) }
            ?: FaceData(coordinates = emptyList(), confidence = emptyList(), note = null)

        val rollingVitals = (obj["rolling_vitals"] as? JsonObject)?.let {
            json.decodeFromJsonElement(MapSerializer(String.serializer(), Waveform.serializer()), it)
        }

        return VitalLensResult(
            face = face,
            vitals = decodeDynamicMap(obj["vitals"], Vital.serializer(), json),
            waveforms = decodeDynamicMap(obj["waveforms"], Waveform.serializer(), json),
            time = emptyList(),
            fps = obj["fps"]?.jsonPrimitive?.doubleOrNull,
            modelUsed = obj["model_used"]?.jsonPrimitive?.contentOrNull,
            state = (obj["state"] as? JsonObject)?.let { json.decodeFromJsonElement(StateDataSerializer, it) },
            message = obj["message"]?.jsonPrimitive?.contentOrNull,
            sampleCount = obj["n"]?.jsonPrimitive?.intOrNull,
            rollingVitals = rollingVitals,
        )
    }

    private fun <T> decodeDynamicMap(
        element: JsonElement?,
        serializer: KSerializer<T>,
        json: Json,
    ): Map<String, T> {
        val obj = element as? JsonObject ?: return emptyMap()
        return buildMap {
            for ((key, value) in obj) {
                runCatching { json.decodeFromJsonElement(serializer, value) }
                    .onSuccess { put(key, it) }
            }
        }
    }

    override fun serialize(encoder: Encoder, value: VitalLensResult) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("VitalLensResult can only be encoded to JSON")
        val json = output.json

        output.encodeJsonElement(
            buildJsonObject {
                put("face", json.encodeToJsonElement(FaceData.serializer(), value.face))
                put("time", json.encodeToJsonElement(ListSerializer(Double.serializer()), value.time))
                value.fps?.let { put("fps", it) }
                value.modelUsed?.let { put("modelUsed", it) }
                value.state?.let { put("state", json.encodeToJsonElement(StateDataSerializer, it)) }
                value.message?.let { put("message", it) }
                value.sampleCount?.let { put("sampleCount", it) }
                put(
                    "waveforms",
                    json.encodeToJsonElement(MapSerializer(String.serializer(), Waveform.serializer()), value.waveforms),
                )
                put(
                    "vitals",
                    json.encodeToJsonElement(MapSerializer(String.serializer(), Vital.serializer()), value.vitals),
                )
                value.rollingVitals?.let {
                    put(
                        "rolling_vitals",
                        json.encodeToJsonElement(MapSerializer(String.serializer(), Waveform.serializer()), it),
                    )
                }
            },
        )
    }
}
