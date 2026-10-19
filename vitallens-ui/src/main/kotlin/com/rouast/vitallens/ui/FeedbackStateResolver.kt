package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.ConnectionThresholds
import kotlinx.coroutines.CancellationException
import com.rouast.vitallens.inference.Rect

private const val FACE_CONF_THRESHOLD = 0.5
private const val PPG_CONF_THRESHOLD = 0.5

/**
 * Whether an HRV metric may be shown. HRV is only shown when it is confident itself and heart rate
 * is confident too: users should not see an HRV without a heart rate. Keep this rule in sync with
 * the other VitalLens SDKs.
 */
internal fun isHrvShown(hrvConf: Double?, hrConf: Double?, hrvThreshold: Double, hrThreshold: Double): Boolean {
    if (hrvConf == null || hrConf == null) return false
    return hrvConf >= hrvThreshold && hrConf >= hrThreshold
}

/** Hint shown when the signal is weak. Keep wording in sync with the other SDKs. */
internal const val LOW_CONFIDENCE_MESSAGE = "Low confidence.\nUse steady light, move closer, and hold still."

/** Notice shown when the stream fell back to eco mode. Keep wording in sync with the other SDKs. */
internal const val SLOW_CONNECTION_MESSAGE = "Slow connection. Switched to Eco mode."

/** How long (ms) a notice is kept before regular messages replace it. */
internal const val NOTICE_DURATION_MS = 5_000L

/** Whether a message may be shown now: regular messages wait while a notice is showing, issues never do. */
internal fun shouldShowMessage(nowMs: Long, noticeUntilMs: Long, force: Boolean): Boolean =
    force || nowMs >= noticeUntilMs

/** Whether a running scan or monitor has stopped receiving results for too long, which happens on connections far too slow. */
internal fun isStalled(
    waitingForResults: Boolean,
    msSinceLastResult: Long,
    timeoutMs: Long = ConnectionThresholds.STALL_TIMEOUT_MS,
): Boolean = waitingForResults && msSinceLastResult > timeoutMs

/**
 * Runs a stream session and reports failures to [onError]. A cancellation is not a failure: it
 * means the session was stopped or replaced on purpose (e.g. falling back to eco mode), so it is
 * rethrown instead of being reported.
 */
internal suspend fun runSession(onError: (Exception) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }
}

private val Rect.midX: Float get() = x + width / 2f
private val Rect.midY: Float get() = y + height / 2f

/**
 * Whether the detected face is adequately centered and large enough within the frame. Shared here
 * rather than duplicated inside `ScanScreen.kt` and `MonitorScreen.kt`, since both screens need
 * the same face-quality check to decide when to show a "hold still"/"adjust position" prompt.
 */
internal fun isFaceGood(box: Rect?): Boolean {
    if (box == null) return false
    val midX = box.midX
    val midY = box.midY
    return midX > 0.3f && midX < 0.7f && midY > 0.3f && midY < 0.7f && box.width > 0.15f
}

/** The average of the last [windowSize] samples in [history], or 0.0 if [history] is empty. */
internal fun rollingAverage(history: List<Double>, windowSize: Int): Double {
    if (history.isEmpty()) return 0.0
    val window = history.takeLast(windowSize)
    return window.sum() / window.size
}

/** Whether recent PPG or face confidence has dropped low enough to warrant a recovery prompt. */
internal fun isLowSignal(
    avgPpgConf: Double,
    avgFaceConf: Double,
    ppgThreshold: Double = PPG_CONF_THRESHOLD,
    faceThreshold: Double = FACE_CONF_THRESHOLD,
): Boolean = avgPpgConf < ppgThreshold || avgFaceConf < faceThreshold
