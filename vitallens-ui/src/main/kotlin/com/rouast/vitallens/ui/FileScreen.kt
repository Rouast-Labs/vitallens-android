package com.rouast.vitallens.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.inference.model.VitalLensResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/** The state of the file processing workflow. */
enum class FileState { IDLE, PROCESSING, COMPLETED, ERROR }

/**
 * Parses a raw [VitalLensResult] into UI-ready vitals and stats. Unlike `ScanScreen.kt`'s
 * `completeScan`, no confidence threshold is applied here — a vital is shown whenever it was
 * computed at all, with primary vitals that were never computed dropped entirely (rather than
 * shown as "--").
 */
internal fun resolveFileVitals(result: VitalLensResult, fallbackFps: Double): Triple<List<ResolvedVital>, List<ResolvedVital>, ScanStats> {
    val hrMeta = VitalInfoCache.getInfo("heart_rate")
    val rrMeta = VitalInfoCache.getInfo("respiratory_rate")

    val primaryVitals = listOfNotNull(
        result.heartRate?.value?.let { value ->
            ResolvedVital(
                id = "hr",
                title = hrMeta?.displayName ?: "Heart Rate",
                value = value,
                unit = hrMeta?.unit?.uppercase() ?: "BPM",
                format = "%.0f",
                confidence = result.heartRate?.confidence,
                emoji = hrMeta?.emoji ?: "❤️",
            )
        },
        result.respiratoryRate?.value?.let { value ->
            ResolvedVital(
                id = "rr",
                title = rrMeta?.displayName ?: "Respiration",
                value = value,
                unit = rrMeta?.unit?.uppercase() ?: "RPM",
                format = "%.0f",
                confidence = result.respiratoryRate?.confidence,
                emoji = rrMeta?.emoji ?: "🫁",
            )
        },
    )

    val secondaryVitals = listOfNotNull(
        resolveFileSecondaryVital("hrv_sdnn", result.hrvSdnn?.value, result.hrvSdnn?.confidence),
        resolveFileSecondaryVital("hrv_rmssd", result.hrvRmssd?.value, result.hrvRmssd?.confidence),
        resolveFileSecondaryVital("ie_ratio", result.vitals["ie_ratio"]?.value, result.vitals["ie_ratio"]?.confidence),
    )

    val fps = result.fps ?: fallbackFps
    val count = result.sampleCount ?: result.time.size
    val duration = if (fps > 0) count / fps else 0.0
    val faceConfs = result.face.confidence.orEmpty()
    val avgFaceConf = if (faceConfs.isEmpty()) 0.0 else faceConfs.average()

    return Triple(primaryVitals, secondaryVitals, ScanStats(duration, count, avgFaceConf))
}

private fun resolveFileSecondaryVital(id: String, value: Double?, confidence: Double?): ResolvedVital? {
    if (value == null) return null
    val meta = VitalInfoCache.getInfo(id) ?: return null
    return ResolvedVital(
        id = id,
        title = meta.shortName,
        value = value,
        unit = meta.unit.uppercase(),
        format = if (id == "ie_ratio") "%.2f" else "%.0f",
        confidence = confidence,
        emoji = meta.emoji,
    )
}

private class FileController(
    private val context: android.content.Context,
    private val apiKey: String?,
    private val proxyUrl: HttpUrl?,
    private val method: String,
    private val baseUrl: HttpUrl?,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(FileState.IDLE)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var primaryVitals by mutableStateOf(emptyList<ResolvedVital>())
        private set
    var secondaryVitals by mutableStateOf(emptyList<ResolvedVital>())
        private set
    var scanStats by mutableStateOf(ScanStats(0.0, 0, 0.0))
        private set
    var finalResult by mutableStateOf<VitalLensResult?>(null)
        private set

    fun reset() {
        state = FileState.IDLE
    }

    fun process(uri: Uri) {
        state = FileState.PROCESSING
        scope.launch {
            try {
                val client = VitalLens(
                    context = context,
                    apiKey = apiKey,
                    method = method,
                    proxyUrl = proxyUrl,
                    strategy = resolveCustomStrategy(apiKey, proxyUrl, method, overrideFps = null, baseUrl = baseUrl),
                )
                val result = client.processVideoFile(uri)
                finalResult = result
                val (primary, secondary, stats) = resolveFileVitals(result, VitalLensMode.STANDARD.fps)
                primaryVitals = primary
                secondaryVitals = secondary
                scanStats = stats
                state = FileState.COMPLETED
            } catch (e: Exception) {
                errorMessage = e.message ?: "An unknown error occurred."
                state = FileState.ERROR
            }
        }
    }
}

/**
 * Lets the user select a video file, processes it via the VitalLens API in batch mode, and
 * displays the resulting vital signs and waveforms.
 *
 * Android's system document/media picker already covers photo library and file browser sources in
 * one flow, so this launches a single `ActivityResultContracts.GetContent()` picker directly
 * rather than presenting a separate source-selection step first.
 */
@Composable
fun FileScreen(
    modifier: Modifier = Modifier,
    apiKey: String? = null,
    proxyUrl: HttpUrl? = null,
    method: String = "vitallens",
    baseUrl: HttpUrl? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { FileController(context, apiKey, proxyUrl, method, baseUrl, scope) }

    val pickVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { controller.process(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(VitalLensColors.Background)) {
        when (controller.state) {
            FileState.IDLE -> StartScreen(
                title = "VitalLens File Processing",
                subtitle = "Estimate vital signs from\na video file",
                timingHintLabel = "Processing time\ndepends on video.",
                startButtonLabel = "Select Video File",
                currentMode = VitalLensMode.STANDARD,
                onModeChange = {},
                onStart = { pickVideoLauncher.launch("video/*") },
                instruction1 = GuideInstruction(Icons.Filled.AccountCircle, "Ensure one face is\nclearly visible."),
                instruction2 = GuideInstruction(Icons.Filled.PauseCircleFilled, "Ensure the face\ndoes not move much."),
                showModeToggle = false,
            )

            FileState.PROCESSING -> FileProcessingIndicator()

            FileState.COMPLETED -> ResultScreen(
                title = "Scan Complete",
                primaryVitals = controller.primaryVitals,
                secondaryVitals = controller.secondaryVitals,
                ppgWaveform = controller.finalResult?.ppg?.data?.map { it.toDouble() },
                respWaveform = controller.finalResult?.resp?.data?.map { it.toDouble() },
                stats = controller.scanStats,
                onDone = { controller.reset() },
            )

            FileState.ERROR -> FileErrorView(message = controller.errorMessage, onTryAgain = { controller.reset() })
        }
    }
}

/** Shown while a selected video file is being processed. */
@Composable
fun FileProcessingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color.White)
            Text("Processing video...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Shown when file processing fails, with an option to retry. */
@Composable
fun FileErrorView(message: String, onTryAgain: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Error", style = MaterialTheme.typography.titleMedium, color = Color.Red)
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            Button(
                onClick = onTryAgain,
                colors = ButtonDefaults.buttonColors(containerColor = VitalInfoCache.brandBlue),
            ) {
                Text("Try Again")
            }
        }
    }
}
