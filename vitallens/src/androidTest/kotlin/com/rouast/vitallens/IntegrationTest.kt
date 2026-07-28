package com.rouast.vitallens

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rouast.vitallens.camera.FileSource
import com.rouast.vitallens.camera.PassiveSource
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.network.ApiInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Real network integration tests against the live VitalLens API. Gated behind
 * VITALLENS_API_KEY/VITALLENS_BASE_URL — both required, not just the key, so these tests always
 * run against an explicitly configured base URL and never implicitly fall back to production.
 * See vitallens/build.gradle.kts's integrationTestCredential() for how these are threaded from
 * local.properties/the shell environment into testInstrumentationRunnerArguments: an Android
 * instrumented test runs in a separate process on a device/emulator that does not inherit the
 * host shell's environment, so InstrumentationRegistry.getArguments() (not System.getenv()) is
 * what the test actually sees.
 *
 * Needs a real Android runtime (Context, MediaMetadataRetriever via FileSource), so this lives
 * under androidTest, not test — same placement rationale as FileProcessorInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationTest {

    private lateinit var context: Context
    private var apiKey: String? = null
    private var baseUrl: String? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        apiKey = args.getString("VITALLENS_API_KEY")?.takeIf { it.isNotBlank() }
        baseUrl = args.getString("VITALLENS_BASE_URL")
            ?.takeIf { it.isNotBlank() && it.toHttpUrlOrNull() != null }
    }

    /** @return the verified (apiKey, baseUrl) pair, having skipped the test if either is missing. */
    private fun requireCredentials(): Pair<String, String> {
        assumeTrue("Skipped: VITALLENS_API_KEY not set.", apiKey != null)
        assumeTrue("Skipped: VITALLENS_BASE_URL not set or invalid.", baseUrl != null)
        return apiKey!! to baseUrl!!
    }

    private fun copyAssetToCache(assetName: String): Uri {
        val outFile = File(context.cacheDir, assetName)
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }

    /**
     * ApiInference's own environment-map-based VITALLENS_BASE_URL lookup defaults to
     * System.getenv(), which (per this class's own doc comment) won't see anything on a real
     * device/emulator — pass the resolved value through explicitly via ApiInference's existing
     * DI-friendly environment parameter instead of relying on that default.
     */
    private fun makeStrategy(apiKey: String, baseUrl: String): ApiInference =
        ApiInference(apiKey = apiKey, environment = mapOf("VITALLENS_BASE_URL" to baseUrl))

    @Test
    fun processSampleVideoEndToEnd() = runBlocking {
        val (key, url) = requireCredentials()
        val videoUri = copyAssetToCache("sample_video_2.mp4")

        val client = VitalLens(context, apiKey = key, method = "vitallens-2.0", strategy = makeStrategy(key, url))

        val result = client.processVideoFile(videoUri)

        println("[Integration] API response: ${result.message ?: "Success"}")
        println("[Integration] Model used: ${result.modelUsed ?: "unknown"}")

        assertEquals(30.0, result.fps ?: 0.0, 1.0)
        assertTrue(
            "Result should contain vital or waveform data",
            result.vitals.isNotEmpty() || result.waveforms.isNotEmpty(),
        )

        val expectedSampleCount = 630
        assertEquals("Sample count should be exactly 630", expectedSampleCount, result.sampleCount)

        val faceCoords = requireNotNull(result.face.coordinates) { "Missing face coordinates" }
        val faceConfs = requireNotNull(result.face.confidence) { "Missing face confidence scores" }
        assertEquals("Face coordinates count must match sample count", expectedSampleCount, faceCoords.size)
        assertEquals("Face confidence count must match sample count", expectedSampleCount, faceConfs.size)
        faceCoords.firstOrNull()?.let {
            assertEquals("Bounding box should contain exactly 4 coordinates [minX, minY, maxX, maxY]", 4, it.size)
        }

        val hr = requireNotNull(result.heartRate?.value) { "Missing heart rate" }
        println("[Integration] Heart Rate: $hr")
        assertEquals(60.5, hr, 2.0)

        val rr = requireNotNull(result.respiratoryRate?.value) { "Missing respiratory rate" }
        println("[Integration] Resp Rate: $rr")
        assertEquals(12.0, rr, 1.5)

        val sdnn = requireNotNull(result.hrvSdnn?.value) { "Missing HRV SDNN" }
        println("[Integration] HRV SDNN: $sdnn")
        assertEquals(65.0, sdnn, 10.0)

        val rmssd = requireNotNull(result.hrvRmssd?.value) { "Missing HRV RMSSD" }
        println("[Integration] HRV RMSSD: $rmssd")
        assertEquals(65.0, rmssd, 10.0)

        result.vitals["ie_ratio"]?.value?.let {
            println("[Integration] I:E Ratio: $it")
            assertEquals(1.12, it, 0.15)
        }

        val ppg = requireNotNull(result.ppg) { "Missing PPG waveform" }
        assertTrue("PPG waveform is empty", ppg.data.isNotEmpty())

        val resp = requireNotNull(result.resp) { "Missing Respiratory waveform" }
        assertTrue("Respiratory waveform is empty", resp.data.isNotEmpty())

        assertEquals("Time array length must match expected sample count", expectedSampleCount, result.time.size)
        assertEquals("PPG data length must match expected sample count", expectedSampleCount, ppg.data.size)
        assertEquals("Resp data length must match expected sample count", expectedSampleCount, resp.data.size)
    }

    @Test
    fun processSampleVideoStreamingModeEndToEnd() = runBlocking {
        val (key, url) = requireCredentials()
        val videoUri = copyAssetToCache("sample_video_2.mp4")

        val passiveSource = PassiveSource()
        val client = VitalLens(
            context,
            apiKey = key,
            method = "vitallens-2.0",
            source = passiveSource,
            strategy = makeStrategy(key, url),
        )

        val fileSource = FileSource.from(context, videoUri)
        val frameDurationSec = 1.0 / fileSource.nominalFrameRate

        val stream = client.startStream()

        val results = mutableListOf<VitalLensResult>()
        val resultsMutex = Mutex()

        val injectJob = launch(Dispatchers.IO) {
            var frameCount = 0
            fileSource.frames().collect { bitmap ->
                passiveSource.inject(
                    bitmap = bitmap,
                    orientation = fileSource.orientation,
                    isMirrored = false,
                    timestamp = frameCount * frameDurationSec,
                )
                frameCount++
                delay((frameDurationSec * 1000 / 2.0).toLong())
            }
        }

        val collectJob = launch {
            stream.collect { result ->
                val validCount = resultsMutex.withLock {
                    results.add(result)
                    results.count { (it.heartRate?.value ?: 0.0) > 0.0 }
                }

                val currentHr = result.heartRate?.value ?: 0.0
                println("[Integration Stream] Received result chunk - HR: $currentHr")

                assertNotNull("Streaming chunk should contain local face coordinates", result.face.coordinates)
                assertNotNull("Streaming chunk should contain API face confidence", result.face.confidence)

                val coords = result.face.coordinates
                val confs = result.face.confidence
                if (coords != null && confs != null) {
                    assertEquals(
                        "Coordinate and confidence arrays must be synchronized in the stream",
                        confs.size,
                        coords.size,
                    )
                    assertEquals(
                        "Face data length must match the chunk's time array",
                        result.time.size,
                        coords.size,
                    )
                }

                if (validCount >= 2) cancel()
            }
        }

        withTimeoutOrNull(45_000) { collectJob.join() }

        injectJob.cancel()
        client.stopStream()

        val finalResults = resultsMutex.withLock { results.toList() }
        assertTrue("Should have received streaming results.", finalResults.isNotEmpty())

        val lastResult = finalResults.lastOrNull()
        if (lastResult != null) {
            val finalHr = lastResult.heartRate?.value ?: 0.0
            assertTrue("Streaming result should contain a calculated heart rate.", finalHr > 0.0)
            assertNotNull("Streaming result should contain PPG waveform.", lastResult.ppg?.data)
            assertTrue("Time array should be populated.", lastResult.time.isNotEmpty())
        }
    }
}
