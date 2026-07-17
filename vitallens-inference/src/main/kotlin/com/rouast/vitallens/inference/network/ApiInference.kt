package com.rouast.vitallens.inference.network

import com.rouast.vitallens.core.BufferConfig
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.core.computeBufferConfig
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceOutcome
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.VitalLensResultSerializer
import com.rouast.vitallens.inference.toSessionConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The opaque recurrent state used by [ApiInference]: a raw float array from the API. */
data class ApiState(val data: List<Float>) : InferenceState

/**
 * Remote inference strategy: handles all network communication with the VitalLens API.
 *
 * `actor` -> Mutex-guarded class, per CLAUDE.md's concurrency mapping. Only [resolveConfig] and
 * [bufferConfig] touch shared mutable state ([config]); [resolveModel]/[inferStream]/[inferFile]
 * don't, so they aren't serialized through the same mutex — matching the actual data-race-freedom
 * requirement rather than literally serializing every method the way Swift's actor isolation
 * would (which would otherwise force unrelated concurrent network calls to queue behind
 * each other for no correctness benefit).
 */
class ApiInference(
    private val apiKey: String? = null,
    private val proxyUrl: HttpUrl? = null,
    private val requestedModel: String? = null,
    private val overrideFps: Double? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val environment: Map<String, String> = System.getenv(),
) : InferenceStrategy {

    private val mutex = Mutex()
    private var config: ModelConfig? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val resolvedApiKey: String? = apiKey ?: environment["VITALLENS_API_KEY"]

    private val baseUrl: HttpUrl
        get() = proxyUrl
            ?: environment["VITALLENS_BASE_URL"]?.toHttpUrlOrNull()
            ?: PRODUCTION_BASE_URL

    // MARK: - Configuration

    /** Contacts the API to determine the optimal configuration for the requested model. */
    suspend fun resolveModel(requestedModel: String?): ResolveModelResponse {
        val urlBuilder = baseUrl.newBuilder().addPathSegment("resolve-model")
        if (requestedModel != null) urlBuilder.addQueryParameter("model", requestedModel)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .applyAuthHeaders()
            .build()

        return perform(request, ResolveModelResponse.serializer())
    }

    /** Sends a batch of accumulated video frames to the real-time streaming endpoint. */
    suspend fun inferStream(rawRgbBytes: ByteArray, state: List<Float>?, model: String?): VitalLensResult {
        val url = baseUrl.newBuilder().addPathSegment("stream").build()

        val requestBuilder = Request.Builder()
            .url(url)
            .applyAuthHeaders()
            .addHeader("Content-Type", "application/octet-stream")
            .addHeader("X-Origin", ORIGIN)
            .addHeader("X-Encoding", "gzip")

        if (model != null) requestBuilder.addHeader("X-Model", model)
        if (!state.isNullOrEmpty()) requestBuilder.addHeader("X-State", state.toBase64())

        val request = requestBuilder
            .post(gzip(rawRgbBytes).toRequestBody("application/octet-stream".toMediaType()))
            .build()

        return perform(request, VitalLensResultSerializer)
    }

    /** Uploads a video file chunk to the file processing endpoint. */
    suspend fun inferFile(rawRgbBytes: ByteArray, state: List<Float>?, model: String?): VitalLensResult {
        val url = baseUrl.newBuilder().addPathSegment("file").build()

        val payload = buildJsonObject {
            put("video", Base64.getEncoder().encodeToString(rawRgbBytes))
            put("origin", ORIGIN)
            if (!state.isNullOrEmpty()) put("state", state.toBase64())
            if (model != null) put("model", model)
        }

        // ByteArray (not the String overload) toRequestBody: the String overload adds
        // "; charset=utf-8" to the media type it wasn't given one, since it must declare
        // whatever charset it used to encode the string — an accurate but Swift-parity-breaking
        // header value we don't need since we've already encoded to UTF-8 bytes ourselves.
        val request = Request.Builder()
            .url(url)
            .applyAuthHeaders()
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()

        val result = perform(request, VitalLensResultSerializer)

        // result.time is always empty per VitalLensResult's decode (see model/VitalLensResult.kt),
        // so this condition really just means "sampleCount is present" — ported faithfully from
        // the Swift source, including its side effect of dropping rollingVitals in that case.
        return if (result.time.isEmpty() && result.sampleCount != null) {
            result.copy(time = emptyList(), rollingVitals = null)
        } else {
            result
        }
    }

    // MARK: - InferenceStrategy Conformance

    /** Resolves and caches the configuration for the active model. */
    override suspend fun resolveConfig(): ModelConfig = mutex.withLock {
        val response = resolveModel(requestedModel)
        val resolvedConfig = response.config
        resolvedConfig.modelName = response.resolvedModel
        overrideFps?.let { resolvedConfig.fpsTarget = it }
        config = resolvedConfig
        resolvedConfig
    }

    /** The buffer configuration dictated by the resolved model settings. */
    override suspend fun bufferConfig(): BufferConfig = mutex.withLock {
        val current = config
            ?: throw VitalLensException.ProcessingError("Attempted to access config before resolving.")
        computeBufferConfig(current.toSessionConfig())
    }

    /** Processes a window of frames using the configured API endpoint. */
    override suspend fun infer(
        window: List<Pair<InferenceUnit, InferenceContext>>,
        state: InferenceState?,
        mode: InferenceMode,
        model: String?,
    ): InferenceOutcome {
        val combined = ByteArrayOutputStream()
        for ((unit, _) in window) {
            when (unit) {
                is InferenceUnit.RgbData -> combined.write(unit.data)
                is InferenceUnit.PixelBuffer -> throw VitalLensException.ProcessingError(
                    "ApiInference received raw PixelBuffer. Ensure the Transformer is configured for API mode.",
                )
            }
        }

        val currentState = (state as? ApiState)?.data

        val result = when (mode) {
            InferenceMode.STREAM -> inferStream(combined.toByteArray(), currentState, model)
            InferenceMode.FILE -> inferFile(combined.toByteArray(), currentState, model)
        }

        val nextState = result.state?.data?.let { base64 ->
            runCatching { Base64.getDecoder().decode(base64).toFloatList() }.getOrNull()
        }?.let { ApiState(it) }

        val mappedTimes = window.map { it.second.timestamp }
        val mappedRois = window.map { it.second.roi }

        val returnedSampleCount = result.sampleCount
            ?: result.waveforms.values.firstOrNull()?.data?.size
            ?: mappedTimes.size

        val synthesizedTime = mappedTimes.takeLast(returnedSampleCount)
        val synthesizedRois = mappedRois.takeLast(returnedSampleCount)

        val localFaceCoordinates = synthesizedRois.map { rect ->
            listOf(rect.x.toDouble(), rect.y.toDouble(), rect.maxX.toDouble(), rect.maxY.toDouble())
        }

        val mergedFaceData = FaceData(
            coordinates = localFaceCoordinates,
            confidence = result.face.confidence,
            note = result.face.note,
        )

        val cleanResult = VitalLensResult(
            face = mergedFaceData,
            vitals = result.vitals,
            waveforms = result.waveforms,
            time = synthesizedTime,
            fps = result.fps,
            modelUsed = result.modelUsed,
            state = null,
            message = result.message,
            sampleCount = result.sampleCount,
        )

        return InferenceOutcome(cleanResult, nextState)
    }

    // MARK: - Private Helpers

    private fun Request.Builder.applyAuthHeaders(): Request.Builder {
        if (proxyUrl == null && resolvedApiKey != null) {
            addHeader("X-Api-Key", resolvedApiKey)
        }
        return this
    }

    private suspend fun <T> perform(request: Request, serializer: KSerializer<T>): T {
        try {
            val response = client.newCall(request).await()
            val bodyString = response.body.string()

            when (response.code) {
                in 200..299 -> {}
                401, 403 -> throw VitalLensException.InvalidAPIKey
                429 -> throw VitalLensException.QuotaExceeded
                in 400..499 -> {
                    val message = runCatching {
                        json.decodeFromString(APIErrorResponse.serializer(), bodyString).message
                    }.getOrNull()
                    throw VitalLensException.ClientError(response.code, message)
                }
                in 500..599 -> throw VitalLensException.ServerError(response.code, null)
                else -> throw VitalLensException.NetworkError(IOException("Unexpected response code ${response.code}"))
            }

            return json.decodeFromString(serializer, bodyString)
        } catch (e: VitalLensException) {
            throw e
        } catch (e: Exception) {
            throw VitalLensException.NetworkError(e)
        }
    }

    companion object {
        private val PRODUCTION_BASE_URL = "https://api.rouast.com/vitallens-v3".toHttpUrl()
        private const val ORIGIN = "vitallens-android"
    }
}

private fun gzip(data: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(data) }
    return output.toByteArray()
}

private fun List<Float>.toBase64(): String {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return Base64.getEncoder().encodeToString(buffer.array())
}

private fun ByteArray.toFloatList(): List<Float> {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val floats = mutableListOf<Float>()
    while (buffer.remaining() >= Float.SIZE_BYTES) {
        floats.add(buffer.float)
    }
    return floats
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}
