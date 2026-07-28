package com.rouast.vitallens.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.inference.model.VitalLensResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import kotlin.math.min

private const val SCAN_ROUAST_API_URL = "https://www.rouast.com/api/"
private const val SCAN_DURATION_SECONDS = 30.0
private const val WARM_UP_DURATION_SECONDS = 3.0
private const val RECOVERY_TIMEOUT_SECONDS = 10.0
private const val VITAL_CONF_THRESHOLD = 0.8
private const val HRV_CONF_THRESHOLD = 0.7

/** The operational states of the scanning process. Mirrors Swift's `ScanState`. */
enum class ScanState { IDLE, SEARCHING, WARMING_UP, TRACKING, RECOVERING, ISSUE, COMPLETED }

/** The outcome of evaluating a scan-state transition: the resolved state, message, and strike count. */
internal data class ScanTransitionResult(val state: ScanState, val message: String, val strikeCount: Int)

/**
 * Pure per-frame state-transition rule, extracted from `VitalLensScanView.swift`'s `updateUI`
 * switch statement so it's unit-testable without a live camera/coroutine pipeline. Only called
 * for the four "live" states (searching/warmingUp/tracking/recovering) — idle/completed/issue are
 * guarded by the caller, matching the Swift `default: break`.
 */
internal fun resolveScanTransition(
    currentState: ScanState,
    currentMessage: String,
    goodFace: Boolean,
    isLowSignal: Boolean,
    elapsedInState: Double,
    strikeCount: Int,
    warmUpDuration: Double = WARM_UP_DURATION_SECONDS,
    recoveryTimeout: Double = RECOVERY_TIMEOUT_SECONDS,
): ScanTransitionResult = when (currentState) {
    ScanState.SEARCHING -> if (goodFace) {
        ScanTransitionResult(ScanState.WARMING_UP, "Calibrating... Hold still.", strikeCount)
    } else {
        ScanTransitionResult(ScanState.SEARCHING, currentMessage, strikeCount)
    }

    ScanState.WARMING_UP -> if (elapsedInState >= warmUpDuration) {
        ScanTransitionResult(ScanState.TRACKING, "Scanning...", strikeCount)
    } else {
        ScanTransitionResult(ScanState.WARMING_UP, currentMessage, strikeCount)
    }

    ScanState.TRACKING -> when {
        !goodFace -> ScanTransitionResult(ScanState.RECOVERING, "Adjust position...", strikeCount)
        isLowSignal -> ScanTransitionResult(ScanState.RECOVERING, "Improve lighting...", strikeCount)
        else -> ScanTransitionResult(ScanState.TRACKING, currentMessage, strikeCount)
    }

    ScanState.RECOVERING -> when {
        goodFace && !isLowSignal -> ScanTransitionResult(ScanState.TRACKING, "Scanning...", strikeCount)
        elapsedInState >= recoveryTimeout -> resolveIssueEscalation(strikeCount, "Could not recover conditions.")
        else -> {
            val message = if (!goodFace) "Adjust position..." else "Improve lighting..."
            ScanTransitionResult(ScanState.RECOVERING, message, strikeCount)
        }
    }

    else -> ScanTransitionResult(currentState, currentMessage, strikeCount)
}

/**
 * Handles a transient issue, escalating to a full [ScanState.ISSUE] once retries are exhausted.
 * Mirrors `VitalLensScanView.swift`'s `handleIssue(message:)`.
 */
internal fun resolveIssueEscalation(strikeCount: Int, message: String): ScanTransitionResult {
    val newStrikeCount = strikeCount + 1
    return if (newStrikeCount >= 3) {
        ScanTransitionResult(ScanState.ISSUE, message, newStrikeCount)
    } else {
        ScanTransitionResult(ScanState.SEARCHING, "$message Retrying...", newStrikeCount)
    }
}

/**
 * Owns the scan's mutable state and orchestration — the Compose analogue of
 * `VitalLensScanView`'s `@State` properties and private methods. Created via `remember` so it
 * survives recomposition but not configuration changes, matching this codebase's established
 * per-screen state ownership (see project memory on vitallens-ui architecture decisions).
 */
private class ScanController(
    private val apiKey: String?,
    private val proxyUrl: HttpUrl?,
    private val method: String,
    initialMode: VitalLensMode,
    private val scope: CoroutineScope,
    private val onComplete: (VitalLensResult) -> Unit,
) {
    var scanState by mutableStateOf(ScanState.IDLE)
        private set
    var currentMode by mutableStateOf(initialMode)
    var statusMessage by mutableStateOf("Position your face in the oval")
        private set
    var progress by mutableStateOf(0.0)
        private set
    var primaryVitals by mutableStateOf(emptyList<ResolvedVital>())
        private set
    var secondaryVitals by mutableStateOf(emptyList<ResolvedVital>())
        private set
    var scanStats by mutableStateOf(ScanStats(0.0, 0, 0.0))
        private set

    var client: VitalLens? = null
        private set

    private var accumulatedScanTime = 0.0
    private var lastFrameTimeMs: Long? = null
    private var stateStartTimeMs: Long = System.currentTimeMillis()
    private var strikeCount = 0
    private val ppgConfHistory = mutableListOf<Double>()
    private val respConfHistory = mutableListOf<Double>()
    private val faceConfHistory = mutableListOf<Double>()
    private var totalFramesProcessed = 0

    fun startProcessing() {
        scanState = ScanState.SEARCHING
        statusMessage = "Position your face in the oval"
        progress = 0.0
        accumulatedScanTime = 0.0
        stateStartTimeMs = System.currentTimeMillis()
        lastFrameTimeMs = null
        strikeCount = 0
        ppgConfHistory.clear()
        respConfHistory.clear()
        faceConfHistory.clear()
        totalFramesProcessed = 0
    }

    fun resetToIdle() {
        client?.stopStream()
        client = null
        scanState = ScanState.IDLE
        progress = 0.0
        accumulatedScanTime = 0.0
        strikeCount = 0
        ppgConfHistory.clear()
        respConfHistory.clear()
        faceConfHistory.clear()
        totalFramesProcessed = 0
    }

    fun cancel() = transition(ScanState.ISSUE, "Scan cancelled by user.")

    private fun transition(newState: ScanState, message: String) {
        scanState = newState
        statusMessage = message
        stateStartTimeMs = System.currentTimeMillis()

        if (newState == ScanState.ISSUE) {
            client?.stopStream()
            scope.launch {
                delay(2_000)
                if (scanState == ScanState.ISSUE) resetToIdle()
            }
        }
    }

    private fun handleIssue(message: String) = applyScanTransition(resolveIssueEscalation(strikeCount, message))

    /**
     * A result with `state == SEARCHING` reached from anything other than `SEARCHING` only ever
     * comes from [resolveIssueEscalation] (the per-frame reducer never transitions directly to
     * searching otherwise) — that's the manual-reset "retry" branch Swift's `handleIssue` performs
     * inline rather than through its generic `transition(to:message:)`.
     */
    private fun applyScanTransition(result: ScanTransitionResult) {
        strikeCount = result.strikeCount
        when {
            result.state == ScanState.SEARCHING && scanState != ScanState.SEARCHING -> {
                scanState = ScanState.SEARCHING
                statusMessage = result.message
                progress = 0.0
                accumulatedScanTime = 0.0
                stateStartTimeMs = System.currentTimeMillis()
                lastFrameTimeMs = null
                client?.resetStream()
                ppgConfHistory.clear()
                respConfHistory.clear()
                faceConfHistory.clear()
            }
            result.state == ScanState.ISSUE -> transition(ScanState.ISSUE, result.message)
            result.state != scanState -> transition(result.state, result.message)
            result.message != statusMessage -> statusMessage = result.message
        }
    }

    fun startSession(context: android.content.Context, view: PreviewView) {
        if (client != null) return

        if (apiKey == null && proxyUrl == null) {
            transition(ScanState.ISSUE, "Error: Missing API Key or Proxy URL")
            return
        }

        val newClient = VitalLens(
            context = context,
            apiKey = apiKey,
            method = method,
            proxyUrl = proxyUrl,
            overrideFps = currentMode.fps,
        )
        newClient.onFaceStateChanged = { isPresent -> onFaceStateChanged(isPresent) }
        client = newClient

        scope.launch {
            try {
                val stream = newClient.startStream(preview = view)
                stream.collect { result -> updateUI(result) }
            } catch (e: Exception) {
                transition(ScanState.ISSUE, "Error: ${e.message}")
            }
        }
    }

    private fun onFaceStateChanged(isPresent: Boolean) {
        if (scanState == ScanState.IDLE || scanState == ScanState.COMPLETED || scanState == ScanState.ISSUE) return
        if (!isPresent && scanState != ScanState.SEARCHING) handleIssue("Face lost.")
    }

    private fun updateUI(result: VitalLensResult) {
        val framesInThisUpdate = result.sampleCount ?: result.time.size
        totalFramesProcessed += framesInThisUpdate

        result.face.confidence?.let { faceConfHistory.addAll(it) }
        result.ppg?.confidence?.let { ppgConfHistory.addAll(it.map { c -> c.toDouble() }) }
        result.resp?.confidence?.let { respConfHistory.addAll(it.map { c -> c.toDouble() }) }

        if (scanState == ScanState.IDLE || scanState == ScanState.COMPLETED || scanState == ScanState.ISSUE) return

        val samplesInOneSecond = currentMode.fps.toInt()
        val avgFaceConf = rollingAverage(faceConfHistory, samplesInOneSecond)
        val avgPpgConf = rollingAverage(ppgConfHistory, samplesInOneSecond)
        val lowSignal = isLowSignal(avgPpgConf, avgFaceConf)
        val goodFace = isFaceGood(result.face.boundingBoxes.lastOrNull())

        val now = System.currentTimeMillis()
        val elapsedInState = (now - stateStartTimeMs) / 1000.0

        if (scanState == ScanState.TRACKING || scanState == ScanState.RECOVERING) {
            lastFrameTimeMs?.let { last ->
                accumulatedScanTime += (now - last) / 1000.0
                progress = min(accumulatedScanTime / SCAN_DURATION_SECONDS, 1.0)
            }
            lastFrameTimeMs = now

            if (accumulatedScanTime >= SCAN_DURATION_SECONDS) {
                client?.stopStream()
                completeScan(result)
                return
            }
        } else {
            lastFrameTimeMs = null
        }

        applyScanTransition(
            resolveScanTransition(
                currentState = scanState,
                currentMessage = statusMessage,
                goodFace = goodFace,
                isLowSignal = lowSignal,
                elapsedInState = elapsedInState,
                strikeCount = strikeCount,
            ),
        )
    }

    private fun completeScan(result: VitalLensResult) {
        val hrMeta = VitalInfoCache.getInfo("heart_rate")
        val rrMeta = VitalInfoCache.getInfo("respiratory_rate")

        primaryVitals = listOf(
            ResolvedVital(
                id = "hr",
                title = hrMeta?.displayName ?: "Heart Rate",
                value = (result.heartRate?.confidence ?: 0.0)
                    .takeIf { it >= VITAL_CONF_THRESHOLD }
                    ?.let { result.heartRate?.value },
                unit = hrMeta?.unit?.uppercase() ?: "BPM",
                format = "%.0f",
                confidence = result.heartRate?.confidence,
                emoji = hrMeta?.emoji ?: "❤️",
            ),
            ResolvedVital(
                id = "rr",
                title = rrMeta?.displayName ?: "Respiration",
                value = (result.respiratoryRate?.confidence ?: 0.0)
                    .takeIf { it >= VITAL_CONF_THRESHOLD }
                    ?.let { result.respiratoryRate?.value },
                unit = rrMeta?.unit?.uppercase() ?: "RPM",
                format = "%.0f",
                confidence = result.respiratoryRate?.confidence,
                emoji = rrMeta?.emoji ?: "🫁",
            ),
        )

        secondaryVitals = listOfNotNull(
            resolveSecondaryVital("hrv_sdnn", result.hrvSdnn?.value, result.hrvSdnn?.confidence ?: 0.0, HRV_CONF_THRESHOLD),
            resolveSecondaryVital("hrv_rmssd", result.hrvRmssd?.value, result.hrvRmssd?.confidence ?: 0.0, HRV_CONF_THRESHOLD),
            resolveSecondaryVital("ie_ratio", result.vitals["ie_ratio"]?.value, result.vitals["ie_ratio"]?.confidence ?: 0.0, VITAL_CONF_THRESHOLD),
        )

        val globalAvgFace = if (faceConfHistory.isEmpty()) 0.0 else faceConfHistory.average()
        scanStats = ScanStats(
            duration = totalFramesProcessed / (result.fps ?: currentMode.fps),
            sampleCount = totalFramesProcessed,
            avgFaceConf = globalAvgFace,
        )

        scanState = ScanState.COMPLETED
        onComplete(result)
    }

    private fun resolveSecondaryVital(id: String, value: Double?, confidence: Double, threshold: Double): ResolvedVital? {
        if (confidence < threshold || value == null) return null
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
}

/**
 * A guided, fixed-duration scanning experience: captures video, evaluates face placement and
 * lighting, and returns a single aggregated result upon completion. Mirrors
 * `VitalLensScanView.swift`.
 */
@Composable
fun ScanScreen(
    onComplete: (VitalLensResult) -> Unit,
    modifier: Modifier = Modifier,
    apiKey: String? = null,
    proxyUrl: HttpUrl? = null,
    method: String = "vitallens",
    mode: VitalLensMode = VitalLensMode.ECO,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ScanController(apiKey, proxyUrl, method, mode, scope, onComplete) }

    DisposableEffect(Unit) {
        onDispose { controller.client?.stopStream() }
    }

    when (controller.scanState) {
        ScanState.IDLE -> StartScreen(
            title = "VitalLens Vitals Scan",
            subtitle = "Estimate your vital signs using\nonly your camera",
            timingHintLabel = "Scan takes\n~30 seconds.",
            startButtonLabel = "Start Scan",
            currentMode = controller.currentMode,
            onModeChange = { controller.currentMode = it },
            onStart = { controller.startProcessing() },
            modifier = modifier,
        )

        ScanState.COMPLETED -> ResultScreen(
            title = "Scan Complete",
            primaryVitals = controller.primaryVitals,
            secondaryVitals = controller.secondaryVitals,
            stats = controller.scanStats,
            onDone = { controller.resetToIdle() },
            modifier = modifier,
        )

        else -> ScanUILayer(
            controller = controller,
            onCameraViewAvailable = { view -> controller.startSession(context, view) },
            modifier = modifier,
        )
    }
}

@Composable
private fun ScanUILayer(controller: ScanController, onCameraViewAvailable: (PreviewView) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(modifier = Modifier.fillMaxSize(), onViewAvailable = onCameraViewAvailable)

        CutoutOverlay(modifier = Modifier.fillMaxSize())

        if (controller.scanState == ScanState.TRACKING ||
            controller.scanState == ScanState.RECOVERING ||
            controller.scanState == ScanState.WARMING_UP
        ) {
            ScanProgressRing(progress = controller.progress, modifier = Modifier.align(Alignment.Center))
        }

        ScanTopBar(
            state = controller.scanState,
            onCancel = { controller.cancel() },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Text(
            controller.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ScanTopBar(state: ScanState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.vitallens_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .clickable { uriHandler.openUri(SCAN_ROUAST_API_URL) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = Color.White)
            }
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ScanStatusBadge(state = state)
        }
    }
}

@Composable
private fun ScanProgressRing(progress: Double, modifier: Modifier = Modifier, ringWidth: Dp = 320.dp, ringHeight: Dp = 450.dp) {
    val brandBlue = VitalInfoCache.brandBlue
    Canvas(modifier = modifier.size(ringWidth, ringHeight)) {
        drawArc(
            color = brandBlue,
            startAngle = -90f,
            sweepAngle = 360f * progress.toFloat(),
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** A blurred overlay with an oval cutout guiding face placement. Mirrors `CutoutOverlay`. */
@Composable
private fun CutoutOverlay(modifier: Modifier = Modifier, cutoutWidth: Dp = 320.dp, cutoutHeight: Dp = 450.dp) {
    Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRect(Color.Black.copy(alpha = 0.5f))
        val cutoutWidthPx = cutoutWidth.toPx()
        val cutoutHeightPx = cutoutHeight.toPx()
        drawOval(
            color = Color.Transparent,
            topLeft = Offset((size.width - cutoutWidthPx) / 2f, (size.height - cutoutHeightPx) / 2f),
            size = Size(cutoutWidthPx, cutoutHeightPx),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
    }
}

/** Displays the current scan state as a pulsing colored dot with a label. Mirrors `ScanStatusBadge`. */
@Composable
fun ScanStatusBadge(state: ScanState, modifier: Modifier = Modifier) {
    val isPulsing = state == ScanState.SEARCHING || state == ScanState.WARMING_UP || state == ScanState.RECOVERING
    val transition = rememberInfiniteTransition(label = "scan_status_pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.2f else 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan_status_pulse_scale",
    )

    val color = when (state) {
        ScanState.IDLE, ScanState.COMPLETED -> Color.Gray
        ScanState.SEARCHING -> VitalInfoCache.brandBlue
        ScanState.WARMING_UP -> Color(0xFF9C27B0)
        ScanState.TRACKING -> Color(0xFF4CAF50)
        ScanState.RECOVERING -> Color(0xFFFF9800)
        ScanState.ISSUE -> Color.Red
    }
    val text = when (state) {
        ScanState.IDLE -> "Idle"
        ScanState.SEARCHING -> "Searching"
        ScanState.WARMING_UP -> "Calibrating"
        ScanState.TRACKING -> "Scanning"
        ScanState.RECOVERING -> "Recovering"
        ScanState.ISSUE -> "Issue"
        ScanState.COMPLETED -> "Done"
    }

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer(scaleX = if (isPulsing) scale else 1f, scaleY = if (isPulsing) scale else 1f)
                .background(color, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}
