package com.rouast.vitallens.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.core.WaveformMode
import com.rouast.vitallens.inference.model.VitalLensResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.util.Locale

private const val MONITOR_ROUAST_API_URL = "https://www.rouast.com/api/"
private const val VITAL_CONF_THRESHOLD = 0.8
private const val HRV_CONF_THRESHOLD = 0.7
private const val FACE_CONF_THRESHOLD = 0.5

/** The operational state of the live monitor. Mirrors Swift's `MonitorState`. */
enum class MonitorState { IDLE, SEARCHING, WARMING_UP, TRACKING, ISSUE }

/**
 * The post-face-confidence-check portion of `VitalLensMonitorView.swift`'s `updateUI`: given
 * which vitals are currently confident, decides whether to flag low confidence, keep warming up
 * (waiting for enough buffered samples), or report tracking. The face-confidence-too-low branch
 * is handled separately by the caller since it short-circuits before any of these vitals are read.
 */
internal fun resolveMonitorFeedback(
    hasConfidentHr: Boolean,
    hasConfidentRr: Boolean,
    hasConfidentHrv: Boolean,
    showWaveforms: Boolean,
    hasEnoughData: Boolean,
): Pair<MonitorState, String> = when {
    !(hasConfidentHr || hasConfidentRr || hasConfidentHrv) ->
        MonitorState.ISSUE to "Low confidence. Ensure you are well lit and hold still."
    showWaveforms && !hasEnoughData -> MonitorState.WARMING_UP to ""
    else -> MonitorState.TRACKING to "Tracking vitals"
}

/** The width of a [GroupedMetricsTile], or `null` to let it fill remaining space. Mirrors `dynamicTileWidth`. */
internal fun dynamicTileWidth(showWaveforms: Boolean, hasSecondaryVitals: Boolean): Int? =
    if (!showWaveforms) null else if (hasSecondaryVitals) 170 else 110

/** The display format for a vital id. Mirrors `GroupedMetricsTile.init`'s local `format(for:)`. */
internal fun monitorVitalFormat(id: String?): String =
    if (id == "ie_ratio" || id == "hrv_lfhf") "%.2f" else "%.0f"

private data class BufferedPoint(val value: Double, val confidence: Double, val displayTimeMs: Double)

private fun nowMs(): Double = SystemClock.elapsedRealtime().toDouble()

/**
 * Owns the monitor's mutable state and orchestration — the Compose analogue of
 * `VitalLensMonitorView`'s `@State` properties and private methods.
 */
private class MonitorController(
    private val context: android.content.Context,
    private val apiKey: String?,
    private val proxyUrl: HttpUrl?,
    private val method: String,
    val showWaveforms: Boolean,
    initialMode: VitalLensMode,
    private val bufferOffsetSeconds: Double,
    private val windowSizeSeconds: Double,
    private val minDisplayDurationSeconds: Double,
    private val scope: CoroutineScope,
) {
    var currentMode by mutableStateOf(initialMode)
    var isProcessing by mutableStateOf(false)
        private set
    var monitorState by mutableStateOf(MonitorState.IDLE)
        private set
    var feedbackMessage by mutableStateOf("")
        private set

    private var isFaceCurrentlyDetected = false

    var hrValue by mutableStateOf<Double?>(null)
        private set
    var hrConf by mutableStateOf(0.0)
        private set
    var rrValue by mutableStateOf<Double?>(null)
        private set
    var rrConf by mutableStateOf(0.0)
        private set
    var sdnnValue by mutableStateOf<Double?>(null)
        private set
    var sdnnConf by mutableStateOf(0.0)
        private set
    var rmssdValue by mutableStateOf<Double?>(null)
        private set
    var rmssdConf by mutableStateOf(0.0)
        private set
    var ieRatioValue by mutableStateOf<Double?>(null)
        private set
    var ieRatioConf by mutableStateOf(0.0)
        private set

    var ppgHistory by mutableStateOf(emptyList<Double>())
        private set
    var respHistory by mutableStateOf(emptyList<Double>())
        private set
    private val ppgConfHistory = mutableListOf<Double>()
    private val respConfHistory = mutableListOf<Double>()

    var receivedVitals by mutableStateOf(emptySet<String>())
        private set

    var client: VitalLens? = null
        private set
    private var playbackJob: kotlinx.coroutines.Job? = null
    private val ppgQueue = ArrayDeque<BufferedPoint>()
    private val respQueue = ArrayDeque<BufferedPoint>()
    private var timeAnchorVideoTime: Double? = null
    private var timeAnchorRealTimeMs = 0.0

    private val maxHistoryPoints: Int get() = (windowSizeSeconds * currentMode.fps).toInt()
    private val requiredSamplesForDisplay: Int get() = (minDisplayDurationSeconds * currentMode.fps).toInt()

    val hasSecondaryVitals: Boolean
        get() = receivedVitals.any { it == "hrv_sdnn" || it == "hrv_rmssd" || it == "ie_ratio" }
    val hasEnoughData: Boolean get() = ppgHistory.size >= requiredSamplesForDisplay

    val isHrReady: Boolean get() = hrValue != null && hrConf >= VITAL_CONF_THRESHOLD
    val isRrReady: Boolean get() = rrValue != null && rrConf >= VITAL_CONF_THRESHOLD
    val isSdnnReady: Boolean get() = sdnnValue != null && sdnnConf >= HRV_CONF_THRESHOLD
    val isRmssdReady: Boolean get() = rmssdValue != null && rmssdConf >= HRV_CONF_THRESHOLD
    val isIeReady: Boolean get() = ieRatioValue != null && ieRatioConf >= VITAL_CONF_THRESHOLD
    val isPpgReady: Boolean get() = rollingAverage(ppgConfHistory, ppgConfHistory.size) >= VITAL_CONF_THRESHOLD && hasEnoughData
    val isRespReady: Boolean get() = rollingAverage(respConfHistory, respConfHistory.size) >= VITAL_CONF_THRESHOLD && hasEnoughData

    val dynamicMessage: String
        get() {
            if (feedbackMessage.isNotEmpty()) return feedbackMessage
            if (monitorState == MonitorState.WARMING_UP) {
                val progress = ((ppgHistory.size.toDouble() / requiredSamplesForDisplay) * 100).toInt().coerceAtMost(100)
                return "Calibrating signals... ($progress%)"
            }
            return ""
        }

    fun startProcessing() {
        isProcessing = true
        monitorState = MonitorState.SEARCHING
        feedbackMessage = "Face the camera, ensure good lighting and hold still."
    }

    fun stopProcessing() {
        isProcessing = false
        monitorState = MonitorState.IDLE
        feedbackMessage = ""
        client?.stopStream()
        playbackJob?.cancel()
        client = null
        resetUI()
    }

    private fun clearMeasurements() {
        hrValue = null; hrConf = 0.0
        rrValue = null; rrConf = 0.0
        sdnnValue = null; sdnnConf = 0.0
        rmssdValue = null; rmssdConf = 0.0
        ieRatioValue = null; ieRatioConf = 0.0
        ppgHistory = emptyList(); ppgQueue.clear()
        respHistory = emptyList(); respQueue.clear()
        ppgConfHistory.clear()
        respConfHistory.clear()
        timeAnchorVideoTime = null
        receivedVitals = emptySet()
    }

    private fun resetUI() {
        clearMeasurements()
        isFaceCurrentlyDetected = false
    }

    fun startSession(view: PreviewView) {
        if (client != null || !isProcessing) return

        val newClient = VitalLens(
            context = context,
            apiKey = apiKey,
            method = method,
            proxyUrl = proxyUrl,
            overrideFps = currentMode.fps,
            waveformMode = WaveformMode.Incremental,
        )
        newClient.onFaceStateChanged = { isPresent -> onFaceStateChanged(isPresent) }
        client = newClient

        if (bufferOffsetSeconds > 0) {
            playbackJob = scope.launch { runPlaybackLoop() }
        }

        scope.launch {
            try {
                val stream = newClient.startStream(preview = view)
                stream.collect { result -> updateUI(result) }
            } catch (e: Exception) {
                stopProcessing()
            }
        }
    }

    /**
     * Face-loss handling deliberately deviates from `VitalLensMonitorView.swift` here: iOS sets a
     * hard [MonitorState.ISSUE], vitallens.js instead falls back to a soft `searching` retry
     * ("Changed to gracefully fallback to searching instead of fatal issue" per its own comment).
     * User-approved to follow the JS behavior — see project memory on vitallens-ui architecture
     * decisions.
     */
    private fun onFaceStateChanged(isPresent: Boolean) {
        if (!isProcessing) return
        isFaceCurrentlyDetected = isPresent
        if (!isPresent) {
            monitorState = MonitorState.SEARCHING
            feedbackMessage = "Face the camera and hold still."
            client?.resetStream()
            clearMeasurements()
        } else if (monitorState == MonitorState.SEARCHING || monitorState == MonitorState.IDLE) {
            monitorState = MonitorState.SEARCHING
            feedbackMessage = "Face detected, analyzing..."
        }
    }

    private fun updateUI(result: VitalLensResult) {
        if (!isProcessing || !isFaceCurrentlyDetected) return

        receivedVitals = receivedVitals + result.vitals.keys

        val faceConfs = result.face.confidence.orEmpty()
        val currentFaceConf = faceConfs.lastOrNull() ?: 0.0

        if (currentFaceConf < FACE_CONF_THRESHOLD) {
            monitorState = MonitorState.ISSUE
            feedbackMessage = "Face not clear. Hold still."
            return
        }

        if (showWaveforms) queueWaveformData(result)

        result.heartRate?.let { hrValue = it.value; hrConf = it.confidence }
        result.respiratoryRate?.let { rrValue = it.value; rrConf = it.confidence }
        result.hrvSdnn?.let { sdnnValue = it.value; sdnnConf = it.confidence }
        result.hrvRmssd?.let { rmssdValue = it.value; rmssdConf = it.confidence }
        result.vitals["ie_ratio"]?.let { ieRatioValue = it.value; ieRatioConf = it.confidence }

        val (newState, newMessage) = resolveMonitorFeedback(
            hasConfidentHr = hrConf >= VITAL_CONF_THRESHOLD,
            hasConfidentRr = rrConf >= VITAL_CONF_THRESHOLD,
            hasConfidentHrv = sdnnConf >= HRV_CONF_THRESHOLD || rmssdConf >= HRV_CONF_THRESHOLD,
            showWaveforms = showWaveforms,
            hasEnoughData = hasEnoughData,
        )
        monitorState = newState
        feedbackMessage = newMessage
    }

    private fun queueWaveformData(result: VitalLensResult) {
        val ppgChunk = result.ppg?.data.orEmpty()
        val ppgConfs = result.ppg?.confidence.orEmpty()
        val respChunk = result.resp?.data.orEmpty()
        val respConfs = result.resp?.confidence.orEmpty()
        if (ppgChunk.isEmpty() && respChunk.isEmpty()) return

        if (bufferOffsetSeconds > 0) {
            if (timeAnchorVideoTime == null) {
                result.time.firstOrNull()?.let { firstTime ->
                    timeAnchorVideoTime = firstTime
                    timeAnchorRealTimeMs = nowMs()
                }
            }
            val anchorVideoTime = timeAnchorVideoTime ?: return
            result.time.forEachIndexed { index, time ->
                val targetDisplayTimeMs = timeAnchorRealTimeMs + (time - anchorVideoTime) * 1000.0 + bufferOffsetSeconds * 1000.0
                if (index < ppgChunk.size) {
                    val conf = (ppgConfs.getOrNull(index) ?: ppgConfs.lastOrNull() ?: 0f).toDouble()
                    ppgQueue.addLast(BufferedPoint(ppgChunk[index].toDouble(), conf, targetDisplayTimeMs))
                }
                if (index < respChunk.size) {
                    val conf = (respConfs.getOrNull(index) ?: respConfs.lastOrNull() ?: 0f).toDouble()
                    respQueue.addLast(BufferedPoint(respChunk[index].toDouble(), conf, targetDisplayTimeMs))
                }
            }
        } else {
            appendHistory(ppgChunk.map { it.toDouble() }, ppgConfs.map { it.toDouble() }, isPpg = true)
            appendHistory(respChunk.map { it.toDouble() }, respConfs.map { it.toDouble() }, isPpg = false)
        }
    }

    private fun appendHistory(values: List<Double>, confs: List<Double>, isPpg: Boolean) {
        if (values.isEmpty()) return
        if (isPpg) {
            ppgHistory = (ppgHistory + values).let { if (it.size > maxHistoryPoints) it.takeLast(maxHistoryPoints) else it }
            ppgConfHistory.addAll(confs)
            if (ppgConfHistory.size > maxHistoryPoints) repeat(ppgConfHistory.size - maxHistoryPoints) { ppgConfHistory.removeAt(0) }
        } else {
            respHistory = (respHistory + values).let { if (it.size > maxHistoryPoints) it.takeLast(maxHistoryPoints) else it }
            respConfHistory.addAll(confs)
            if (respConfHistory.size > maxHistoryPoints) repeat(respConfHistory.size - maxHistoryPoints) { respConfHistory.removeAt(0) }
        }
    }

    private suspend fun runPlaybackLoop() {
        while (scope.isActive) {
            val now = nowMs()

            val newPpgVals = mutableListOf<Double>()
            val newPpgConfs = mutableListOf<Double>()
            while (ppgQueue.isNotEmpty() && now >= ppgQueue.first().displayTimeMs) {
                val point = ppgQueue.removeFirst()
                newPpgVals.add(point.value)
                newPpgConfs.add(point.confidence)
            }
            appendHistory(newPpgVals, newPpgConfs, isPpg = true)

            val newRespVals = mutableListOf<Double>()
            val newRespConfs = mutableListOf<Double>()
            while (respQueue.isNotEmpty() && now >= respQueue.first().displayTimeMs) {
                val point = respQueue.removeFirst()
                newRespVals.add(point.value)
                newRespConfs.add(point.confidence)
            }
            appendHistory(newRespVals, newRespConfs, isPpg = false)

            delay(16)
        }
    }
}

/**
 * A real-time, continuous monitoring interface for vital signs: a live camera feed with
 * dynamically updating physiological estimates and waveforms. Mirrors
 * `VitalLensMonitorView.swift`. The debug crop/ROI overlay is dropped — `VitalLens.kt` has no
 * `debugMode`/`debugLatestCrop` (see its own docstring), so there's nothing to display.
 */
@Composable
fun MonitorScreen(
    modifier: Modifier = Modifier,
    apiKey: String? = null,
    proxyUrl: HttpUrl? = null,
    method: String = "vitallens",
    showWaveforms: Boolean = true,
    initialMode: VitalLensMode = VitalLensMode.ECO,
    bufferOffsetSeconds: Double = 0.15,
    windowSizeSeconds: Double = 8.0,
    minDisplayDurationSeconds: Double = 6.0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        MonitorController(
            context, apiKey, proxyUrl, method, showWaveforms, initialMode,
            bufferOffsetSeconds, windowSizeSeconds, minDisplayDurationSeconds, scope,
        )
    }

    DisposableEffect(Unit) {
        onDispose { controller.stopProcessing() }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (controller.isProcessing) {
            CameraPreview(modifier = Modifier.fillMaxSize(), onViewAvailable = { view -> controller.startSession(view) })
        }

        if (controller.monitorState == MonitorState.IDLE) {
            StartScreen(
                title = "VitalLens Vitals Monitor",
                subtitle = "Estimate your vital signs using\nonly your camera",
                timingHintLabel = "Scan runs\ncontinuously.",
                startButtonLabel = "Start Monitor",
                currentMode = controller.currentMode,
                onModeChange = { controller.currentMode = it },
                onStart = { controller.startProcessing() },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                MonitorTopBar(state = controller.monitorState, onStop = { controller.stopProcessing() })
                Spacer(Modifier.weight(1f))
                if (controller.monitorState == MonitorState.SEARCHING || controller.monitorState == MonitorState.ISSUE) {
                    SearchOvalGuide(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.weight(1f))
                }
                MonitorBottomMetrics(controller = controller)
            }
        }
    }
}

@Composable
private fun MonitorTopBar(state: MonitorState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.vitallens_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .clickable { uriHandler.openUri(MONITOR_ROUAST_API_URL) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Cancel, contentDescription = "Stop", tint = Color.White)
            }
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MonitorStatusBadge(state = state)
        }
    }
}

/** A dashed oval guide shown while searching for a face. Mirrors the `middleGapLayer` outline. */
@Composable
private fun SearchOvalGuide(modifier: Modifier = Modifier, width: Dp = 220.dp, height: Dp = 300.dp) {
    Canvas(modifier = modifier.size(width, height).padding(8.dp)) {
        drawOval(
            color = Color.White.copy(alpha = 0.4f),
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))),
        )
    }
}

@Composable
private fun MonitorBottomMetrics(controller: MonitorController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.dynamicMessage.isNotEmpty()) {
            Text(
                controller.dynamicMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        val tileWidth = dynamicTileWidth(controller.showWaveforms, controller.hasSecondaryVitals)?.dp
        val tileModifier = if (tileWidth != null) Modifier.width(tileWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()

        Row(
            modifier = Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (controller.showWaveforms) {
                WaveformContainer(
                    vitalId = "ppg_waveform",
                    history = controller.ppgHistory,
                    isReady = controller.isPpgReady,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            GroupedMetricsTile(
                primaryId = "heart_rate", primaryValue = controller.hrValue, isPrimaryReady = controller.isHrReady,
                secondary1Id = "hrv_sdnn".takeIf { it in controller.receivedVitals }, secondary1Value = controller.sdnnValue, isSecondary1Ready = controller.isSdnnReady,
                secondary2Id = "hrv_rmssd".takeIf { it in controller.receivedVitals }, secondary2Value = controller.rmssdValue, isSecondary2Ready = controller.isRmssdReady,
                modifier = tileModifier,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (controller.showWaveforms) {
                WaveformContainer(
                    vitalId = "respiratory_waveform",
                    history = controller.respHistory,
                    isReady = controller.isRespReady,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            GroupedMetricsTile(
                primaryId = "respiratory_rate", primaryValue = controller.rrValue, isPrimaryReady = controller.isRrReady,
                secondary1Id = "ie_ratio".takeIf { it in controller.receivedVitals }, secondary1Value = controller.ieRatioValue, isSecondary1Ready = controller.isIeReady,
                secondary2Id = null, secondary2Value = null, isSecondary2Ready = false,
                modifier = tileModifier,
            )
        }
    }
}

@Composable
private fun GroupedMetricsTile(
    primaryId: String,
    primaryValue: Double?,
    isPrimaryReady: Boolean,
    secondary1Id: String?,
    secondary1Value: Double?,
    isSecondary1Ready: Boolean,
    secondary2Id: String?,
    secondary2Value: Double?,
    isSecondary2Ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val pInfo = VitalInfoCache.getInfo(primaryId)

    Row(
        modifier = modifier.background(VitalLensColors.Panel, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pInfo?.shortName ?: primaryId, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                pInfo?.unit?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.6f))
                }
            }
            if (isPrimaryReady && primaryValue != null) {
                Text(
                    String.format(Locale.US, monitorVitalFormat(primaryId), primaryValue),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            } else {
                Text(
                    "--",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.3f),
                )
            }
        }

        if (secondary1Id != null || secondary2Id != null) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                secondary1Id?.let { SecondaryMetric(it, secondary1Value, isSecondary1Ready) }
                secondary2Id?.let { SecondaryMetric(it, secondary2Value, isSecondary2Ready) }
            }
        }
    }
}

@Composable
private fun SecondaryMetric(id: String, value: Double?, isReady: Boolean) {
    val meta = VitalInfoCache.getInfo(id)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(meta?.shortName ?: id, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            meta?.unit?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.6f))
            }
        }
        if (isReady && value != null) {
            Text(
                String.format(Locale.US, monitorVitalFormat(id), value),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        } else {
            Text("--", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.3f))
        }
    }
}

/** Displays the current monitor state as a pulsing colored dot with a label. Mirrors `StatusBadge`. */
@Composable
fun MonitorStatusBadge(state: MonitorState, modifier: Modifier = Modifier) {
    val isPulsing = state == MonitorState.SEARCHING || state == MonitorState.WARMING_UP
    val transition = rememberInfiniteTransition(label = "monitor_status_pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.2f else 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
        label = "monitor_status_pulse_scale",
    )

    val color = when (state) {
        MonitorState.IDLE -> Color.Gray
        MonitorState.SEARCHING -> VitalInfoCache.brandBlue
        MonitorState.WARMING_UP -> Color(0xFF9C27B0)
        MonitorState.TRACKING -> Color(0xFF4CAF50)
        MonitorState.ISSUE -> Color(0xFFFF9800)
    }
    val text = when (state) {
        MonitorState.IDLE -> "Idle"
        MonitorState.SEARCHING -> "Searching..."
        MonitorState.WARMING_UP -> "Calibrating..."
        MonitorState.TRACKING -> "Tracking"
        MonitorState.ISSUE -> "Check Position"
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
