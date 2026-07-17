package com.rouast.vitallens.inference.network

import android.graphics.Bitmap
import com.rouast.vitallens.core.InferenceMode
import com.rouast.vitallens.inference.InferenceContext
import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.InferenceUnit
import com.rouast.vitallens.inference.VitalLensException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream

class ApiInferenceTest {

    private lateinit var server: MockWebServer
    private val capturedUrls = mutableListOf<HttpUrl>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * Redirects every outgoing request's scheme/host/port to [server], regardless of what
     * ApiInference itself computed as the base URL — mirroring the Swift test suite's
     * transport-level URLProtocol interception, which mocks networking without caring which
     * URL was dialed. The pre-redirect URL is captured in [capturedUrls] so tests can still
     * assert on the URL/host ApiInference actually intended to use.
     */
    private fun redirectingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            capturedUrls.add(original.url)
            val redirected = original.url.newBuilder()
                .scheme(server.url("/").scheme)
                .host(server.url("/").host)
                .port(server.url("/").port)
                .build()
            chain.proceed(original.newBuilder().url(redirected).build())
        })
        .build()

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun base64ToFloats(base64: String): List<Float> {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN)
        val floats = mutableListOf<Float>()
        while (buffer.remaining() >= Float.SIZE_BYTES) floats.add(buffer.float)
        return floats
    }

    private val emptySuccessResponse =
        """{ "resolved_model": "test", "config": { "n_inputs": 0, "input_size": 0, "fps_target": 0, "roi_method": "", "supported_vitals": [] } }"""

    private val resolveResponse = """
        {
            "resolved_model": "vitallens-2.0",
            "config": {
                "n_inputs": 4,
                "input_size": 40,
                "fps_target": 30.0,
                "roi_method": "face",
                "supported_vitals": ["heart_rate"]
            }
        }
    """.trimIndent()

    private val validStreamResponse = """
        {
            "face": { "coordinates": [], "confidence": [], "note": "" },
            "vitals": {
                "heart_rate": { "value": 72.0, "confidence": 0.9, "unit": "bpm", "note": "" }
            },
            "waveforms": {},
            "time": [1.0],
            "fps": 30.0,
            "message": "OK"
        }
    """.trimIndent()

    private val responseWithState = """
        {
            "face": { "coordinates": [], "confidence": [], "note": "" },
            "vitals": {},
            "waveforms": {},
            "time": [1.0],
            "state": { "data": "zcxMPc3MTD4=" }
        }
    """.trimIndent()

    private fun makeWindow(size: Int): List<Pair<InferenceUnit, InferenceContext>> {
        val dummyData = ByteArray(size) { 0xAB.toByte() }
        return listOf(InferenceUnit.RgbData(dummyData) to InferenceContext(timestamp = 0.0))
    }

    // MARK: - Initialization & Auth

    @Test
    fun `API key header is set on a direct call`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val api = ApiInference(apiKey = "test_key_123", proxyUrl = null, client = redirectingClient())

        api.resolveModel(requestedModel = null)

        assertEquals("test_key_123", server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `environment base URL is used when proxy is nil`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val env = mapOf("VITALLENS_BASE_URL" to "http://dev.example.com")
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient(), environment = env)

        api.resolveModel(requestedModel = null)

        assertEquals("dev.example.com", capturedUrls.last().host)
        assertEquals("key", server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `environment API key is used when explicit key is nil`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val env = mapOf("VITALLENS_API_KEY" to "env_secret_key")
        val api = ApiInference(apiKey = null, proxyUrl = null, client = redirectingClient(), environment = env)

        api.resolveModel(requestedModel = null)

        assertEquals("env_secret_key", server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `explicit key overrides environment key`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val env = mapOf("VITALLENS_API_KEY" to "env_key")
        val api = ApiInference(apiKey = "explicit_key", proxyUrl = null, client = redirectingClient(), environment = env)

        api.resolveModel(requestedModel = null)

        assertEquals("explicit_key", server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `proxy ignores API key`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val proxy = "https://my-proxy.com".toHttpUrl()
        val api = ApiInference(apiKey = "key", proxyUrl = proxy, client = redirectingClient())

        api.resolveModel(requestedModel = null)

        assertEquals("my-proxy.com", capturedUrls.last().host)
        assertNull(server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `explicit proxy overrides environment base URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(emptySuccessResponse).build())
        val env = mapOf("VITALLENS_BASE_URL" to "http://dev.example.com")
        val proxy = "https://my-proxy.com".toHttpUrl()
        val api = ApiInference(apiKey = "key", proxyUrl = proxy, client = redirectingClient(), environment = env)

        api.resolveModel(requestedModel = null)

        assertEquals("my-proxy.com", capturedUrls.last().host)
        assertNull(server.takeRequest().headers["X-Api-Key"])
    }

    // MARK: - Resolve Model

    @Test
    fun `resolveModel sends the model as a query parameter`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(resolveResponse).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())

        val response = api.resolveModel(requestedModel = "vitallens-2.0")

        assertEquals("vitallens-2.0", capturedUrls.last().queryParameter("model"))
        assertEquals("vitallens-2.0", response.resolvedModel)
        assertEquals(4, response.config.nInputs)
    }

    @Test
    fun `resolveConfig and bufferConfig conform to InferenceStrategy`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(resolveResponse).build())
        val strategy: com.rouast.vitallens.inference.InferenceStrategy =
            ApiInference(apiKey = "test", proxyUrl = null, client = redirectingClient())

        val config = strategy.resolveConfig()

        assertEquals(4, config.nInputs)
        assertEquals(40, config.inputSize)
        assertTrue(strategy.bufferConfig().streamMax > 0u)
    }

    // MARK: - Inference Execution

    @Test
    fun `stream request is built with gzip body and state header`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(validStreamResponse).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())
        val dummyState = ApiState(listOf(0.1f, 0.2f))
        val window = makeWindow(1000)
        val rawData = ByteArray(1000) { 0xAB.toByte() }

        api.infer(window, state = dummyState, mode = InferenceMode.STREAM, model = "vitallens-2.0")

        val recorded = server.takeRequest()
        assertTrue(recorded.target.endsWith("/stream"))
        assertEquals("POST", recorded.method)
        assertEquals("application/octet-stream", recorded.headers["Content-Type"])
        assertEquals("gzip", recorded.headers["X-Encoding"])
        assertEquals("vitallens-2.0", recorded.headers["X-Model"])

        val stateHeader = recorded.headers["X-State"]
        assertNotNull(stateHeader)
        val floats = base64ToFloats(stateHeader!!)
        assertEquals(2, floats.size)
        assertEquals(0.1f, floats[0], 0.0001f)

        val bodyBytes = recorded.body!!.toByteArray()
        assertTrue(bodyBytes.size > 2)
        assertEquals(0x1f, bodyBytes[0].toInt() and 0xff)
        assertEquals(0x8b, bodyBytes[1].toInt() and 0xff)
        assertTrue(gunzip(bodyBytes).contentEquals(rawData))
    }

    @Test
    fun `file request is built with JSON body and no stream headers`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(validStreamResponse).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())
        val dummyState = ApiState(listOf(0.5f, 0.6f))
        val window = makeWindow(4)
        val rawData = ByteArray(4) { 0xAB.toByte() }

        api.infer(window, state = dummyState, mode = InferenceMode.FILE, model = "test-model")

        val recorded = server.takeRequest()
        assertTrue(recorded.target.endsWith("/file"))
        assertEquals("application/json", recorded.headers["Content-Type"])
        assertNull(recorded.headers["X-Encoding"])
        assertNull(recorded.headers["X-State"])

        val json = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("vitallens-android", json["origin"]?.jsonPrimitive?.content)
        assertEquals("test-model", json["model"]?.jsonPrimitive?.content)
        assertEquals(Base64.getEncoder().encodeToString(rawData), json["video"]?.jsonPrimitive?.content)
        assertNotNull(json["state"])
    }

    @Test
    fun `infer throws ProcessingError on pixelBuffer input`() = runTest {
        val api = ApiInference(apiKey = "test", proxyUrl = null, client = redirectingClient())
        val window = listOf(InferenceUnit.PixelBuffer(mock<Bitmap>()) to InferenceContext(timestamp = 0.0))

        var thrown: VitalLensException.ProcessingError? = null
        try {
            api.infer(window, state = null, mode = InferenceMode.STREAM, model = null)
        } catch (e: VitalLensException.ProcessingError) {
            thrown = e
        }
        assertNotNull(thrown)
    }

    @Test
    fun `infer parses the returned state`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(responseWithState).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())
        val window = makeWindow(10)

        val outcome = api.infer(window, state = null, mode = InferenceMode.STREAM, model = null)

        val apiState = outcome.newState as? ApiState
        assertNotNull(apiState)
        assertEquals(2, apiState?.data?.size)
    }

    // MARK: - Error Handling

    @Test
    fun `401 maps to InvalidAPIKey`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        val api = ApiInference(apiKey = "bad_key", proxyUrl = null, client = redirectingClient())

        var thrown: Throwable? = null
        try {
            api.resolveModel(requestedModel = null)
        } catch (e: Throwable) {
            thrown = e
        }
        assertEquals(VitalLensException.InvalidAPIKey, thrown)
    }

    @Test
    fun `429 maps to QuotaExceeded`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())

        var thrown: Throwable? = null
        try {
            api.resolveModel(requestedModel = null)
        } catch (e: Throwable) {
            thrown = e
        }
        assertEquals(VitalLensException.QuotaExceeded, thrown)
    }

    @Test
    fun `500 maps to ServerError with the status code`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        val api = ApiInference(apiKey = "key", proxyUrl = null, client = redirectingClient())

        var thrown: VitalLensException.ServerError? = null
        try {
            api.resolveModel(requestedModel = null)
        } catch (e: VitalLensException.ServerError) {
            thrown = e
        }
        assertEquals(500, thrown?.statusCode)
    }
}
